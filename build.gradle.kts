plugins {
    java
    id("socotra-ec-config-developer") version "v0.6.8"
}

`socotra-config-developer` {
    apiUrl.set(System.getenv("SOCOTRA_KERNEL_API_URL") ?: "http://hardcoded-fallback-tenant-url")


    tenantLocator.set("d5225397-ac83-4bb6-8a2c-9cf255c8621c")
    personalAccessToken.set("SOCP_01KEVM2GG0X461BFP699PKBWTN")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.3")
}
