# Spike: E2E login harness (Fabric/NeoForge)

Goal: automate scripted login/join scenarios against a real dedicated server process so we cover mixin + ArgusCore behavior end-to-end.

## Proposed approach
- Use Fabric GameTest harness (headless) to run inside the dedicated server JVM.
- Create a tiny test mod in `:fabric` test sources that:
  - Starts with `argus.smoke=true` flag to auto-stop after tests.
  - Uses the server's `ServerLoginNetworkHandler` to simulate a login by constructing a fake `ClientConnection` and `GameProfile` and invoking `sendSuccessPacket` (Fabric hook point) with mixin active.
  - Observes the disconnect reason (Text) and checks whitelist/ban/cache mutations.
- Scenarios to cover (initial set):
  1) linked+role: should not disconnect; cache remains true.
  2) missing role: login allowed, join kicks; whitelist removal happens.
  3) unlinked but vanilla-whitelisted: login denies with /link token.
  4) stranger: allowed; vanilla gates.
  5) Argus ban: disconnect with ban reason.
- Implementation sketch:
  - Add `fabric-gametest-api-v1` as `testModImplementation` in :fabric.
  - Add `src/testMod/java` with a `@GameTest` class.
  - Build helper to spawn `ServerLoginNetworkHandler` using `new ServerLoginNetworkHandler(server, new ClientConnection(NetworkSide.SERVERBOUND), profile)`, then call `sendSuccessPacket(profile)`; capture disconnect via injected connection (override `disconnect(Text)` in a test subclass of ClientConnection to record the message instead of closing sockets).
  - For join path, call `ServerPlayNetworkHandler`? Simpler: call ArgusCore.onPlayerJoin directly in test after login outcome.
- Acceptance: tests must run via `:fabric:gametest` (provided by Fabric Loom); wire into `check` optionally later.

## Open questions
- Will Minecraft classes be accessible in testMod sources with current Loom setup? (likely yes; Loom exposes them to testmod compile/runtime).
- Handling of obfuscation names on NeoForge: separate harness needed? optional follow-up.

## Next steps
1) Add testMod source set under :fabric with gametest dependency.
2) Implement FakeClientConnection capturing disconnect message.
3) Implement 2-3 scenarios as POC.
4) Decide whether to wire into `check` by default or gated by `-Pargus.e2e=true`.

