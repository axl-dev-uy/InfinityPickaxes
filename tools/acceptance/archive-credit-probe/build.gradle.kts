plugins { java }

group = "com.infinitygear.acceptance"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
    compileOnly(files("../../../build/libs/InfinityGear-2.0.0-SNAPSHOT-archives-api-v1.jar"))
}

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }
tasks.compileJava { options.release = 25 }
