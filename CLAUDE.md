# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands
- Build: `./gradlew build`
- Run client: `./gradlew runClient`
- Run client in background: `nohup ./gradlew runClient > /dev/null 2>&1 &`
- Check running client: `pgrep -f "java.*runClient"`
- Stop client: `pkill -f "java.*runClient"`
- Run server: `./gradlew runServer`
- Run tests: `./gradlew test`
- Run single test: `./gradlew test --tests "dev.butterflysky.TestClassName.testMethodName"`

## Log Locations
- Latest client logs: `run/logs/latest.log`
- Client crash reports: `run/crash-reports/`
- Java errors: `run/hs_err_pid*.log`

## Version Control
- Use `jj` instead of `git` for all version control operations
- Check status: `jj status`
- Create commit: `jj commit -m "<type>(scope): short description"`

### Commit Message Format
```
<type>(optional scope): <short description>

- Optional detailed point 1
- Optional detailed point 2

<optional footer>
```

- Type: must be one of feat, fix, chore, refactor, docs, style, test, perf, ci, build, revert
- Scope: lowercase term indicating affected area (e.g., api, auth, ui)
- Description: lowercase, no ending punctuation, 50 chars max
- Use present tense (e.g., "add feature" not "added feature")

## Code Style Guidelines
- Kotlin uses object declarations for singletons
- Java classes use PascalCase, functions use camelCase
- Use Fabric's logger via LoggerFactory.getLogger()
- Keep dependency versions pinned as configured in gradle.properties
- Java target compatibility is Java 21
- Kotlin target JVM version is 21
- Mixin annotations require proper import from org.spongepowered.asm
- Organized imports: stdlib first, then third-party libraries
- Error handling: use Kotlin's Result type when appropriate

## Version Pinning (Cursor Rule)
Do not change versions of existing libraries in build.gradle or gradle.properties. When adding dependencies, select the most recent compatible version.

## Allowed Documentation URLs
The following documentation sites are approved for fetching:
- jda.wiki - Discord JDA library documentation