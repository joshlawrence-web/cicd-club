import asyncio
import csv
import datetime
import enum
import functools
import json
import os
import pathlib
import re
import sys
import textwrap
import traceback
import typing
import zipfile

from typing import Any

import dotenv
import httpx

class LogLevel(enum.Enum):
    UNKNOWN = "❓"
    OK = "✅"
    WARN = "⚠️"
    FAIL = "🔴"

    def __str__(self):
        return f"{self.icon}"

    @property
    def icon(self):
        return self.value

    @property
    def level(self):
        return self.name

OK, WARN, FAIL = LogLevel.OK, LogLevel.WARN, LogLevel.FAIL


def cli(command: str, name: str):
    """
    Decorator for CLI Commands that adds error handling and responses
    """
    def wrapper(fn):
        @functools.wraps(fn)
        async def wrapped(parser):
            print("Initializing...")
            started = datetime.datetime.now()

            # Convert argparse namespace to kwargs dict
            kwargs = {k:v for (k, v)
                      in parser.parse_args().__dict__.items()
                      if v is not None}

            try:
                # Run CLI command with kwargs
                await fn(**kwargs)
            except SocotraToolMultiException as err:
                print("\n" + "-"*80)
                for error in err.errors:
                    print(f" {err.icon} {type(error).__name__} {error}")
                    if not isinstance(error, (SocotraToolException, httpx.HTTPError)):
                        traceback.print_exception(error)
                        print("")
                sys.exit(f"{err.level} {name} failed {elapsed_since(started)}: {len(err.errors)} errors")
            except SocotraToolException as err:
                print("\n" + "-"*80)
                sys.exit(f"{err.level} {name} failed {elapsed_since(started)}: {err}")
            except httpx.HTTPStatusError as err:
                print("\n" + "-"*80)
                sys.exit(f"{FAIL} {name} failed {elapsed_since(started)}: HTTP {err.response.status_code} {err.response.reason_phrase}")
            except httpx.HTTPError as err:
                print("\n" + "-"*80)
                sys.exit(f"{FAIL} {name} failed {elapsed_since(started)}: {type(err).__name__} {err}")
            except Exception as err:
                print("\n" + "-"*80)
                traceback.print_exception(err)
                sys.exit(f"{FAIL} {name} error! {elapsed_since(started)}")
            else:
                print("\n" + "-"*80)
                print(f"{OK} {name} successful! {elapsed_since(started)}")
        return wrapped
    return wrapper

class SocotraToolException(Exception):
    """Error executing Socotra CLI tool"""
    def __init__(self, error: str, level: LogLevel = LogLevel.FAIL):
        super().__init__(error)
        self.level = level

    @property
    def icon(self):
        return self.level.icon

class SocotraToolWarning(SocotraToolException):
    """Warning executing Socotra CLI tool"""
    def __init__(self, error: str):
        super().__init__(error, level=LogLevel.WARN)


class SocotraToolMultiException(SocotraToolException):
    """Error executing Socotra CLI tool"""
    def __init__(self, errors: list[Exception], *args, **kwargs):
        super().__init__(f"Errors ({len(errors)}", *args, **kwargs)
        self.errors = errors

class SocotraApiException(SocotraToolException):
    """Error in API other than HTTP Status Exception"""
    # TODO: also capture return code/etc

class SocotraConfigException(SocotraToolException):
    """Error handling Socotra config files"""


class ConfigPath:
    path: pathlib.Path
    _datamodel: "DataModel" = None

    def __init__(self, path: pathlib.Path | str | None = None):
        path = pathlib.Path(path) if path else pathlib.Path.cwd()
        if path.is_dir():
            self.path = path
        elif path.is_file() and zipfile.is_zipfile(path):
            self.zip = zipfile.ZipFile(path)
            self.path = zipfile.Path(self.zip)
        else:
            raise SocotraConfigException(f"{path} is not a valid Socotra EC config")

        if (self.path / "socotra-config").exists():
            self.path = self.path / "socotra-config"

    def __str__(self):
        return f"Socotra-Config({self.path})"

    @property
    def datamodel(self):
        if self._datamodel is None:
            raise SocotraConfigException("Datamodel not initialized!")
        return self._datamodel

    async def load_disk_config(self) -> "DataModel":
        if self._datamodel is None:
            datamodel = await self._load_disk_config(self.path)
            if not datamodel:
                raise SocotraConfigException(f"No config loaded from '{self.path}'")
            self._datamodel = DataModel(datamodel)
        return self._datamodel

    async def _load_disk_config(self, path: pathlib.Path) -> dict[str, Any]:
        config = {}
        for child in path.iterdir():
            if child.name == "config.json":
                try:
                    with child.open() as fp:
                        config.update(json.load(fp))
                except Exception as err:
                    print(f"{WARN} WARNING: Failed to load config file: ", err)
                    raise SocotraConfigException(f"Failed to load config file: '{child}'") from err

            elif child.is_dir():
                config[child.name] = await self._load_disk_config(child)

        return config

    def bootstrap_table(self, static_name: str) -> dict:
        # TODO: cache parsed tables
        # TODO: convert data to column types?
        table = self.datamodel.resource(static_name)
        instances = {
            inst["staticName"]: name
            for (name, inst) in self.datamodel["bootstrap"]["resources"]["resourceInstances"].items()
        }
        instance = instances[static_name]
        table_path = self.path / "bootstrap" / "resources" / "resourceFiles" / (table["resourceType"] + "s") / (instance + ".csv")
        # TODO: use chardet or errors=ignore instead of hardcoding utf-8-sig?
        with table_path.open(mode='r', encoding='utf-8-sig') as fp:
            table = csv.DictReader(fp)
            rows = list(table)
        return rows




class DataModel:
    datamodel: dict[str, Any]

    # cached lookups
    _lookup: dict[str, dict] = None

    ELEMENTS = {"accounts",
                "products", "policyLines",
                "exposures", "exposureGroups",
                "coverages", "coverageTerms",
                "dataTypes"}

    RESOURCES = {"constraintTables", "rangeTables", "tables",
                 "documentTemplates", "templateSnippets",
                 "secrets"}

    def __init__(self, datamodel: dict[str, Any]):
        self.datamodel = datamodel

    def __getitem__(self, key: str):
        if key not in self.datamodel and (key + "s") in self.datamodel:
            key = key + "s"
        return self.datamodel[key]

    def __contains__(self, key: str):
        if key not in self.datamodel and (key + "s") in self.datamodel:
            key = key + "s"
        return self.datamodel.__contains__(key)

    def get(self, key: str, _default: Any = None):
        if key not in self.datamodel and (key + "s") in self.datamodel:
            key = key + "s"
        return self.datamodel.get(key, _default)

    def keys(self):
        return self.datamodel.keys()

    def values(self):
        return self.datamodel.values()

    def items(self):
        return self.datamodel.items()

    @property
    def lookup(self):
        if self._lookup is None:
            elements = {
                name: self._element(category, name, self[category][name])
                for category in self.ELEMENTS
                for (name, element) in self.get(category, {}).items()
            }
            resources = {
                name: self._resource(resource_type, name, self[resource_type][name])
                for resource_type in self.RESOURCES
                for (name, resource) in self.get(resource_type, {}).items()
            }
            self._lookup = elements | resources
        return self._lookup

    def _element(self, category: str, name: str, element: dict):
        element = {
            "name": name,
            "category": category[:-1],
            "displayName": element.get("displayName", camel_to_title(name)),
            **element
        }
        for key in ("data", "staticData"):
            if key in element:
                element[key] = {
                    name: {
                        "name": name,
                        "displayName": field.get("displayName", camel_to_title(name)),
                        **field
                    } for (name, field) in element[key].items()
                }
        if category == "coverageTerms" and element.get("value"):
            element["value"] = {
                "name": name,
                "displayName": element["displayName"],
                **element["value"]
            }
        return element

    def elements(self) -> dict[str, dict]:
        return {name: self.lookup[name] for category in self.ELEMENTS for name in self.datamodel.get(category, {})}

    def element(self, name: str) -> str:
        name = self.strip_quantifier(name)
        return self.elements()[name]

    def _resource(self, resource_type: str, name: str, resource: dict):
        return {
            "name": name,
            "resourceType": resource_type[:-1],
            "displayName": resource.get("displayName", camel_to_title(name)),
            **resource
        }

    def resources(self):
        return {name: self.lookup[name] for resource_type in self.RESOURCES for name in self.datamodel.get(resource_type, {})}

    def resource(self, name: str):
        return self.resources()[name]

    def defaults(self, product: str = None):
        config_defaults = {key: val for (key, val) in self.items() if key.startswith("default") and val}
        if product:
            config_defaults.update({key: val for (key, val) in self.element(product).items() if key.startswith("default") and val})
        return {self._defaults_key(key): val for (key, val) in config_defaults.items()}

    def _defaults_key(self, key: str):
        return key[7].lower() + key[8:]

    def strip_quantifier(self, name: str) -> str:
        return name[:-1] if name[-1] in ("+*?!") else name




class PAT(httpx.Auth):
    """HTTPX Authenticator for Personal Access Token"""
    token: str

    def __init__(self, token: str):
        self.token = token

    def auth_flow(self, request: httpx.Request):
        request.headers["Authorization"] = f"Bearer {self.token}"
        yield request


async def print_tenant_user_details(client: httpx.AsyncClient, locator: str | None = None):
    user, tenant = await asyncio.gather(
        fetch_whoami(client),
        fetch_tenant_details(client, locator),
        return_exceptions=True,
    )

    print("  API URL:", client.base_url)

    if user and not isinstance(user, Exception):
        print("  User:", user["locator"])
        print("       ", user["userName"], "(SERVICE ACCOUNT)" if user.get("serviceAccount") else "")
        if user.get("firstName") or user.get("lastName"):
            print("       ", user['firstName'], user['lastName'])
        if user.get("roles"):
            print(" Roles:", ", ".join(sorted(user["roles"])))
        if locator and locator not in user.get("tenants"):
            print(f"{WARN} WARNING: User does not have access to tenant '{locator}'!")
    else:
        print(f"{WARN} WARNING: Failed to lookup user details: ", user)

    if tenant and not isinstance(tenant, Exception):
        print("Account:", tenant['businessAccount'])
        print(" Tenant:", tenant['locator'])
        print("        ", tenant['name'], f"({tenant['type'].upper()})")
        if tenant.get("description"):
            print("         ", tenant['description'])
    elif locator:
        print(f"{WARN} WARNING: Failed to lookup tenant '{locator}' details: ", tenant)


async def fetch_whoami(client: httpx.AsyncClient):
    response = await client.get("/auth/users/whoami")
    response.raise_for_status()
    return response.json()


async def fetch_tenant_details(client: httpx.AsyncClient, tenant: str):
    if tenant is None:
        return None
    response = await client.get(f"/auth/tenants/{tenant}")
    response.raise_for_status()
    return response.json()



def load_environment(dotenv_path: pathlib.Path | str | None = None, filename: str = ".env", debug: bool = False, **overrides):
    """Load variables from environment and dotenv file (if found), applying overrides"""
    if dotenv_path is None:
        # attempt to find dotenv starting from current dir, and then starting from script
        dotenv_path = dotenv.find_dotenv(filename, usecwd=True) or dotenv.find_dotenv(filename, usecwd=False)
    if not dotenv_path:
        print(f"{WARN} WARNING: Could not locate '{filename}' env file!")
    elif debug:
        print(f"DEBUG: Loading '{dotenv_path}' env file...")

    return {
        **os.environ,
        **dotenv.dotenv_values(dotenv_path),
        **overrides,
    }

def boolean_arg(input: str):
    val = str(input).lower()
    if val in ("y", "yes", "true", "on", "1", "enabled"):
        return True
    elif val in ("n", "no", "false", "off", "0", "disabled"):
        return False
    else:
        raise ValueError(f"Invalid boolean argument '{input}'")

def split_camel_case(value: str) -> str:
    return re.sub('([A-Z][a-z]+)', r' \1', re.sub('([A-Z]+)', r' \1', value)).split()

def camel_to_title(value: str) -> str:
    return " ".join(split_camel_case(value)).title()

def camel_to_underscore(value: str) -> str:
    return "_".join(split_camel_case(value)).lower()

def flatten(nested_list: list) -> list:
    """Flattens an arbitrarily nested list of lists"""
    flattened = []
    for entry in nested_list:
        if isinstance(entry, list):
            flattened.extend(flatten(entry))
        else:
            flattened.append(entry)
    return flattened


class DotDict[K, V](dict[K, V]):
    # TODO: create DotList to go along side to allow list lookups to be flattend into DotDicts as well
    sep: str
    _parent: typing.Self

    def __init__(self, *args, sep:str=".", parent: typing.Self = None, **kwargs):
        self.sep = sep
        self._parent = parent
        for k, v in dict(*args, **kwargs).items():
            self.__setitem__(k, self._value(v))

    def _split(self, key: K) -> tuple[str]:
        if isinstance(key, (list, tuple)):
            return key
        return key.split(self.sep)

    def _join(self, *parts: str) -> str:
        return self.sep.join(parts)

    def _value(self, value: V) -> V:
        if isinstance(value, DotDict):
            return value
        elif isinstance(value, typing.Mapping):
            return self._child(value)
        elif isinstance(value, (list, tuple, set)):
            return type(value)(self._value(v) for v in value)
        return value

    def _child(self, *args, **kwargs):
        return DotDict(*args, **kwargs, sep=self.sep, parent=self)

    def __repr__(self) -> str:
        return "{" + ", ".join(repr(k) + ": " + repr(v) for k, v in self.items()) + "}"

    def __getitem__(self, key: K) -> V:
        if not key or key == self.sep:
            return self
        elif key == self.sep*2:
            return self.parent()
        key, *remaining = self._split(key)
        value = dict.__getitem__(self, key)
        if remaining:
            value = value.__getitem__(remaining)
        return value

    def __setitem__(self, key: K, value: V):
        if not key or key in (self.sep, self.sep*2):
            raise KeyError(f"Cannot set value for key '{key}'")
        key, *remaining = self._split(key)
        if remaining:
            if not dict.__contains__(self, key):
                dict.__setitem__(self, key, self._child())
            self[key].__setitem__(remaining, value)
        else:
            dict.__setitem__(self, key, self._value(value))

    def __contains__(self, key: K) -> bool:
        try:
            self.__getitem__(key)
        except KeyError:
            return False
        else:
            return True

    def __iter__(self):
        yield from self.keys()

    def get(self, key: K, default: V=None) -> V:
        try:
            return self[key]
        except KeyError:
            return default

    def update(self, *dicts, **kwargs):
        for other in [*dicts, kwargs]:
            for key, value in other.items():
                self.__setitem__(key, value)

    def setdefault(self, key: K, default: V=None) -> V:
        try:
            return self[key]
        except KeyError:
            self[key] = default
            return self[key]

    def copy(self) -> typing.Self:
        return DotDict(self, sep=self.sep, parent=self.parent)

    def items(self) -> list[tuple[K, V]]:
        items = []
        for key, value in dict.items(self):
            if isinstance(value, dict):
                items.extend([(self._join(key, child_key), child_val) for child_key, child_val in value.items()])
            else:
                items.append((key, value))
        return items
    def keys(self) -> list[K]:
        return [key for key, _ in self.items()]
    def values(self) -> list[V]:
        return [val for _, val in self.items()]

    # items/keys/values for immediate DotDict only
    def child_items(self) -> list[tuple[K, V]]:
        return dict.items(self)
    def child_keys(self) -> list[K]:
        return [key for key, in self.child_items()]
    def child_values(self) -> list[V]:
        return [val for _, val in self.child_items()]

    # methods to walk up or down the tree of DotDicts
    def parent(self) -> typing.Self:
        if self._parent is None:
            raise KeyError("parent")
        return self._parent

    def children(self) -> list[typing.Self]:
        return [child for child in dict.values(self) if isinstance(child, DotDict)]

    def root(self) -> typing.Self:
        if self._parent is None:
            return self
        return self._parent.root()

    # expand DotDict into regular dictionary
    def expand(self) -> dict:
        return {
            key: self._expand_value(val) for key, val in dict.items(self)
        }
    def _expand_value(self, value: typing.Any) -> typing.Any:
        if isinstance(value, DotDict):
            return value.expand()
        elif isinstance(value, (list, tuple, set)):
            return type(value)(self._expand_value(v) for v in value)
        return value



def dotted_dict(dotted: dict):
    nested = {}
    for dotted_key, val in dotted.items():
        cur = nested
        *sections, key = dotted_key.split(".")
        for section in sections:
            cur = cur.setdefault(section, {})
        cur[key] = val
    return nested

def unique(iterable: list, key: str | typing.Callable = None) -> list:
    """Returns iterable with only unique values, ordering kept intact"""
    iterable = list(iterable)
    if len(iterable) < 2: return iterable

    seen = set()
    filtered = []
    for entry in iterable:
        if isinstance(key, typing.Callable):
            val = key(entry)
        elif isinstance(key, str):
            val = getattr(entry, key)
        else:
            val = entry
        if val not in seen:
            seen.add(val)
            filtered.append(entry)
    return filtered


def listify(maybe_list) -> list:
    """
    Returns a list from either an iterable, or a single item
    """
    if not maybe_list:
        return []
    if isinstance(maybe_list, (list, tuple, set)):
        return list(maybe_list)
    return [maybe_list]

def merge_dicts(*dicts, **kwargs) -> dict:
    """
    Merges any number of dicts together
    """
    data = {}
    for new_dict in dicts:
        if new_dict:
            data.update(**new_dict)
    if kwargs:
        data.update(**kwargs)
    return data




def handle_response(response: httpx.Response, name: str = "API", debug: bool = False):
    """Handle API response, output details and raise exceptions for failures"""
    success = 200 <= response.status_code < 300
    debug = debug or not success

    content_type = response.headers.get("content-type")
    if content_type == "application/json":
        json_body = response.json()
        body = json.dumps(json_body, indent=4, sort_keys=True)
    else:
        json_body = None
        body = response.text

    print(OK if success else FAIL,
          name, "Response:",
          response.status_code, response.reason_phrase,
          format_elapsed(response.elapsed))
    if debug:
        print("  Endpoint:", response.request.method, response.url)
        if response.headers.get("socotra-request-id"):
            print("  RequestID:", response.headers['socotra-request-id'])
        if content_type:
            print("  Content-Type:", content_type)
        if body:
            size = response.headers.get("content-length") or len(response.content)
            print("  Response Body:", format_size(size))
            print(textwrap.indent(body, "  "))

    if json_body:
        # Handle specific error response JSON
        if not success and json_body.get("message"):
            raise SocotraApiException(json_body["message"])

    # Handle other API failure (bad auth, etc)
    response.raise_for_status()

    return response

def format_size(size: int) -> str:
    size = int(size)
    for suffix in ("b", "kb", "mb", "gb", "tb"):
        if size < 1024: break
        size = size / 1024
    return f"({size:.2f}{suffix})"


def format_file_size(file_path: pathlib.Path) -> str:
    return format_size(os.path.getsize(file_path))


def format_elapsed(delta: datetime.timedelta) -> str:
    seconds = delta.total_seconds()
    if seconds < 1.0:
        return f"(in {seconds * 1000:.0f}ms)"
    elif seconds > 60:
        minutes = seconds // 60
        seconds = seconds % 60
        return f"(in {minutes:.0f}m{seconds:.0f}s)"
    return f"(in {seconds:.2f}s)"


def elapsed_since(start: datetime.datetime, end: datetime.datetime | None = None) -> str:
    if end is None:
        end = datetime.datetime.now(start.tzinfo)
    return format_elapsed(end - start)