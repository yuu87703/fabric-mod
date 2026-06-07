# Fabric Mod — Minecraft 1.21.1

A Fabric mod scaffold for Minecraft 1.21.1.

## Prerequisites

- **Java 21** (JDK 21+)
- **Git** (optional)

## Setup / Build

```bash
# 1. Download the Gradle wrapper JAR
gradle wrapper

# Or download manually:
# curl -Lo gradle/wrapper/gradle-wrapper.jar \
#   https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar

# 2. Build the mod
./gradlew build

# The compiled JAR will be in build/libs/
```

## IDE Setup

### IntelliJ IDEA
```bash
./gradlew genSources
./gradlew idea
```
Then open the project folder in IDEA.

### Eclipse
```bash
./gradlew genSources
./gradlew eclipse
```

## Running in Development

```bash
./gradlew runClient
```

## Project Structure

```
src/
├── main/
│   ├── java/com/example/fabricmod/
│   │   ├── FabricMod.java          # Main entry point
│   │   ├── block/ModBlocks.java    # Block registry
│   │   ├── item/ModItems.java      # Item registry
│   │   └── mixin/ExampleMixin.java # Mixin example
│   └── resources/
│       ├── fabric.mod.json         # Mod metadata
│       ├── fabricmod.mixins.json   # Mixin config
│       └── assets/fabricmod/       # Assets (lang, textures, etc.)
└── client/
    └── java/com/example/fabricmod/
        └── FabricModClient.java    # Client entry point
```

## Customization

1. Change `mod_id`, `mod_name`, `mod_author`, etc. in `gradle.properties`
2. Update the package `com.example.fabricmod` to your own namespace
3. Update the group in `build.gradle`
4. Rename the directories accordingly
5. Replace `icon.png` with your own mod icon (64×64 recommended)
