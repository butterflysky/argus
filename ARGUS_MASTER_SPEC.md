# **Argus: Master Specification & Prompt**

Instructions for the AI Agent:  
Act as a Senior Minecraft Mod Developer. I need you to scaffold a comprehensive Multi-Loader (Fabric \+ NeoForge) mod project called "Argus" for Minecraft 1.21+.

## **Developer Identity**

* **Author:** Lina Edwards (butterflysky)  
* **Namespace/Package:** dev.butterflysky.argus

## **Technical Constraints**

1. **Multi-Loader Structure:** Use a Gradle multi-project setup with three modules:  
   * :common (Platform Agnostic Logic, Config, Discord Bot, Database).  
   * :fabric (Fabric Implementation).  
   * :neoforge (Scaffold only).  
2. **Decompilation:** Configure Fabric Loom to use **Vineflower** explicitly. Ensure Gradle tasks are generated so VS Code's Java Language Server (Red Hat) can read the sources and javadocs natively.  
3. **Dependencies:** Use the latest stable versions for Minecraft 1.21.10, Yarn Mappings, and Fabric Loader.

## **1\. Core Logic Rules (Implemented in :common)**

### **A. The Source of Truth (Local Cache)**

* Maintain argus\_db.json mapped as Map\<UUID, PlayerData\>.  
* **CRITICAL:** Login checks must **READ ONLY** from this cache. NEVER block the login thread for Discord API calls.  
* **Data Safety:** Before saving, rename existing argus\_db.json \-\> .bak. On load, if main file fails, try .bak.

### **B. The Permission Gate**

1. **OP Bypass:** OPs always allowed.  
2. **Linked User:** Check PlayerData.hasAccess. If false, Kick ("Access Denied: Missing Discord Role").  
3. **Unlinked User:**  
   * **Legacy (In Vanilla Whitelist):** Allow temporarily. Kick with Token ("Verification Required: \!link \<token\>").  
   * **Stranger:** Kick with application\_message.

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

**File:** neoforge/build.gradle

* Basic setup only.

### **STEP 5: Resources**

**File:** fabric/src/main/resources/fabric.mod.json

* ID: argus, Name: "Argus", Authors: \["Lina Edwards (butterflysky)"\].  
* Entrypoint: dev.butterflysky.argus.fabric.ArgusFabric.

Please proceed to generate these files.
