# Publishing Lancet

Lancet is a Gradle bytecode-weaving library. Its reusable artifacts are Maven
artifacts (JARs), not a standalone Android AAR: `lancet-plugin` must run during
the consumer project's build, while `lancet-base` supplies the hook API.
Adding only an AAR would not enable weaving.

## Publish from GitHub Actions

Update `lancet_version` in `build.gradle`, create a version tag, and push it:

```shell
git tag 1.0.7
git push origin 1.0.7
```

The workflow in `.github/workflows/publish.yml` publishes `lancet-base`,
`lancet-weaver`, and `lancet-plugin` to the repository's GitHub Packages Maven
registry.

## Consume from another project

GitHub Packages requires a token with `read:packages` permission. Add the
repository to both plugin and dependency resolution in `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven {
            url = uri('https://maven.pkg.github.com/lghkele/lancet')
            credentials {
                username = providers.gradleProperty('gpr.user')
                        .orElse(System.getenv('GITHUB_ACTOR')).get()
                password = providers.gradleProperty('gpr.key')
                        .orElse(System.getenv('GITHUB_TOKEN')).get()
            }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri('https://maven.pkg.github.com/lghkele/lancet')
            credentials {
                username = providers.gradleProperty('gpr.user')
                        .orElse(System.getenv('GITHUB_ACTOR')).get()
                password = providers.gradleProperty('gpr.key')
                        .orElse(System.getenv('GITHUB_TOKEN')).get()
            }
        }
        google()
        mavenCentral()
    }
}
```

Then apply the plugin and add the base API in the consuming Android project:

```groovy
plugins {
    id 'me.ele.lancet' version '1.0.7'
}

dependencies {
    compileOnly 'me.ele:lancet-base:1.0.7'
}
```

For older Gradle builds, use the equivalent `buildscript` classpath syntax.
Use `compileOnly` because the API is consumed at weave time and is not intended
to add a runtime dependency to the APK.

For a public repository, JitPack is an alternative that does not require a
GitHub Packages token. Add `https://jitpack.io` to the consumer project's
repositories and use the Git tag as the version:

```groovy
buildscript {
    repositories {
        maven { url = uri('https://jitpack.io') }
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.github.lghkele.lancet:lancet-plugin:1.0.7'
    }
}

apply plugin: 'me.ele.lancet'

dependencies {
    compileOnly 'com.github.lghkele.lancet:lancet-base:1.0.7'
}
```

JitPack also serves these artifacts as JARs; it does not turn this Gradle
weaving library into an Android AAR.

## Local verification

```shell
./gradlew clean uploadAll
```

This installs all artifacts into the local Maven repository without GitHub
credentials.
