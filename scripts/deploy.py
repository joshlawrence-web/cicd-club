#!/usr/bin/env -S uv run --script
# /// script
#   requires-python = ">=3.13"
#   dependencies = [ "httpx", "jellyfish", "more-itertools", "openpyxl", "python-dateutil",  "python-dotenv", "python-ulid", "typing-extensions" ]
# ///

import asyncio
import argparse
import pathlib

import httpx

from lib import *


@cli("deploy", "Deployment")
async def deploy(config: pathlib.Path, api: str, tenant: str, token: str, overwrite: bool = False):
    """Post configuration archive to deployment API"""
    with open(config, 'rb') as config_zip:
        async with httpx.AsyncClient(base_url=api, auth=PAT(token), timeout=None) as client:
            await print_tenant_user_details(client, tenant)
            if overwrite:
                print("  Overwrite: ⚠ Enabled!")
            print("  Config:", config.name, format_file_size(config))

            print(f"Deploying...")
            response = await client.post(
                 f"/config/{tenant}/deployments/deploy",
                 params={"overwrite": overwrite},
                 files={"file": (config.name, config_zip, "application/zip")})

    handle_response(response, name="Deployment", debug=True)

    # Handle deployment failure response (isSuccess=false)
    if not response.json().get("deploymentResult", {}).get("isSuccess"):
        raise SocotraApiException("Unsuccessful deployment (isSuccess: false)")


# CLI entrypoint
if __name__ == "__main__":
    env = load_environment()

    parser = argparse.ArgumentParser(
        description="Deploy configuration archive to tenant",
        epilog="https://docs.socotra.com/configuration/generalTopics/deployment.html")

    parser.add_argument("config", type=pathlib.Path,
        help="Path to deployment config archive (ex config.zip)")

    parser.add_argument("--api",
        default=env.get("SOCOTRA_KERNEL_API_URL", "https://api-ec-sandbox.socotra.com"),
        metavar="API-BASE-URL",
        help="Base API URL for Socotra Environment (ex --api https://api-ec-sandbox.socotra.com)")
    parser.add_argument("--tenant",
        default=env.get("SOCOTRA_KERNEL_TENANT_LOCATOR"),
        metavar="TENANT-LOCATOR",
        help="Tenant Locator UUID to deploy config (ex --tenant XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX)")
    parser.add_argument("--token",
        default=env.get("SOCOTRA_KERNEL_ACCESS_TOKEN"),
        metavar="PERSONAL-ACCESS-TOKEN",
        help="Personal Access Token (ex --token SOCP_XXXXXXXXXXXXXXXXXXXXXXXXXX)")

    parser.add_argument("--overwrite", type=boolean_arg,
        metavar="OVERWRITE-ENABLED",
        nargs="?",
        const="true", # If just --overwrite given, treat as true
        help="Overwrite config on tenant and allow unsafe changes (ex --overwrite true)")

    asyncio.run(deploy(parser))