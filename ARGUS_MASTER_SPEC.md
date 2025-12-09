# **Argus: Master Specification & Prompt**

Instructions for the AI Agent:  
Act as a Senior Minecraft Mod Developer. I need you to scaffold a comprehensive Multi-Loader (Fabric \+ NeoForge) mod project called "Argus" for Minecraft 1.21+.

## **Developer Identity**

* **Author:** Lina Edwards (butterflysky)  
* **Namespace/Package:** dev.butterflysky.argus

## **Technical Constraints**

1. **Multi-Loader Structure:** Use a Gradle multi-project setup with three modules, **Fabric and NeoForge treated as first-class and kept behaviorally in lockstep**:  
   * :common (Platform Agnostic Logic, Config, Discord Bot, Database).  
   * :fabric (Fabric implementation).  
   * :neoforge (NeoForge implementation).  
2. **Decompilation:** Configure Fabric Loom to use **Vineflower** explicitly. Ensure Gradle tasks are generated so VS Code's Java Language Server (Red Hat) can read the sources and javadocs natively.  
3. **Dependencies:** Use the latest stable versions for Minecraft 1.21.10, Yarn Mappings, and Fabric Loader.

## **1\. Core Logic Rules (Implemented in :common)**

### **A. The Source of Truth (Local Cache)**

* Maintain argus\_db.json mapped as Map\<UUID, PlayerData\>.  
* **CRITICAL:** Login checks must **READ ONLY** from this cache. NEVER block the login thread for Discord API calls.  
* **Data Safety:** Before saving, rename existing argus\_db.json \-\> .bak. On load, if main file fails, try .bak.

### **B. Whitelist Ownership (Vanilla decides access)**

* Vanilla whitelist is the gate; Argus manages that list by adding/removing entries to reflect Discord state. Mixins should avoid cancelling login except for Argus bans **and the unlinked-but-whitelisted flow below**, letting vanilla enforce the result.
* **OP Bypass:** OPs always allowed.  
* **Discord-linked users:** If cache/Discord shows the whitelist role, Argus (optionally in dry-run) adds/keeps them on the vanilla whitelist. If the role is missing or they left the guild, Argus removes them (dry-run logs + courtesy messaging, no kick).
* **Vanilla-whitelisted but not Discord-linked:** Even though vanilla would allow, Argus should block with a kick message that includes `/link <token>` so players must link; in dry-run, allow but still surface the courtesy link message/token.
* **Dry-run (`enforcementEnabled=false`):** Log what would change and send the "link your Discord" message + token, but do not kick. Whitelist mutations may still be written so vanilla can gate naturally.
* **Discord unavailable or Argus misconfigured:** Never block a login because Discord is down. Still honor Argus cache bans; otherwise fall back to vanilla whitelist/ban logic.
* **Bans:** Argus bans live in the cache and must also mirror to the vanilla ban list so vanilla can enforce even if Argus is offline (future work if not implemented).  

### **C. Identity & Audit**

* PlayerData stores: discordId, hasAccess, isAdmin, mcName, discordName (Global), discordNick (Server).  
* **Audit:** Mutate Cache \-\> Log to Console \-\> Broadcast to Discord Log Channel.  
* **Identity Tracking:** Listen for Discord Name/Nick changes and log them ("Identity Update: \[MC Name\] is now known as \[New Name\]").

## **2\. Implementation Steps**

Please generate the following file structure and contents:

### **STEP 1: Project Root Setup**

**File:** settings.gradle

* Include projects: common, fabric, neoforge.  
* Define root project name: argus.

**File:** build.gradle (Root)

* Apply plugins: java, fabric-loom (apply to subprojects).  
* **Vineflower Config:** (Add this block to the root build script)  
  subprojects {  
      loom {  
          decompilers {  
              vineflower  
          }  
      }  
  }

* Inject org.javacord:javacord:3.8.0 into :common.  
* Apply eclipse and idea plugins to root and subprojects for VS Code support.

### **STEP 2: The Common Module (/common)**

**File:** common/src/main/java/dev/butterflysky/argus/common/ArgusCore.java

* Abstract the logic (use java.util.UUID).  
* **Config:** bot\_token, guild\_id, whitelist/admin\_role\_id, log\_channel\_id.  
* **Database:** Implement PlayerData class.  
* **Discord Bot:** Initialize Javacord. Implement Listeners (MessageCreate, UserRoleAdd, UserChangeName, etc.).  
* **Public Methods:**  
  * onPlayerLogin(UUID, name, isOp, isWhitelisted) \-\> Result.  
  * onPlayerJoin(UUID, isOp) \-\> Greeting.  
  * handleConsoleCommand(...).

### **STEP 3: The Fabric Module (/fabric)**

**File:** fabric/build.gradle

* Depend on project :common.

**File:** fabric/src/main/java/dev/butterflysky/argus/fabric/ArgusFabric.java

* Implements ModInitializer.  
* Registers Event Hooks (ServerLoginConnectionEvents, ServerPlayConnectionEvents).  
* Registers Commands (/argus).  
* Bridges vanilla whitelist logic.

### **STEP 4: The NeoForge Module (/neoforge)**

**Parity rule:** Every Fabric feature/hook must have a NeoForge equivalent; keep mixins, commands, and cache/Discord wiring aligned. When adding/altering logic, update both loaders in the same change.

**File:** neoforge/build.gradle

* Depend on :common; mirror Fabric configuration (version targets, Java/Kotlin level).

**File:** neoforge/src/main/java/dev/butterflysky/argus/neoforge/ArgusNeoForge.java

* Wire shared hooks to NeoForge events (login, join, commands) matching Fabric behavior.

### **STEP 5: Resources**

**File:** fabric/src/main/resources/fabric.mod.json

* ID: argus, Name: "Argus", Authors: \["Lina Edwards (butterflysky)"\].  
* Entrypoint: dev.butterflysky.argus.fabric.ArgusFabric.

Please proceed to generate these files.
