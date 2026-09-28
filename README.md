# AI Companion (Fabric 1.21.1)

A companion that plays Minecraft next to you like a real player. It's driven by an LLM:
**Groq** (free, fast, cloud) or a **local Llama model** (Ollama, LM Studio, llama.cpp).

You talk to it in normal chat. It answers in chat like a player (`<Steve> on it!`), remembers
things across sessions, and actually *does* stuff in the world: follows you, fights mobs, boxes you
in with blocks when you're in trouble, builds houses and towers, chops trees, mines, crafts, hands
you items, and more. It works in **survival** (needs real materials, mines at real speed, gets hungry)
and **creative** (unlimited blocks, instant breaking, flies).

## Quick start

1. Install [Fabric Loader](https://fabricmc.net/use/) for 1.21.1 and put **Fabric API** and
   `ai-companion-x.y.z.jar` in your `mods` folder.
2. Get a free API key at [console.groq.com](https://console.groq.com/keys) (or use a local model,
   see below).
3. In game:
   ```
   /companion config key gsk_your_key_here
   /companion summon Steve
   ```
   Optional: give it a real player's skin: `/companion summon Steve Notch` or `/companion skin Steve jeb_`.
4. Just talk in chat:
   - "hey steve, surround me with blocks and protect me"
   - "build a small house here" / "build a stone tower over there" (look where you mean)
   - "get some wood" / "mine 20 stone" / "craft a pickaxe"
   - "give me your sword" / "drop all your dirt"
   - "remember this spot as home" ... later: "go home"
   - "follow me" / "stay here" / "stop"
   - "let me out" (it removes the box it built)
   - "build a pyramid of sandstone" (it designs custom builds out of shapes)

Your companion hears you when you're within 48 blocks (you don't need to say its name). With several
companions, say a name to talk to one, or "everyone"/"guys" to talk to all of them.

## Using a local Llama model

**Ollama** (recommended for local):
```
ollama pull llama3.1
/companion config provider ollama
/companion config model llama3.1
```
Bigger models follow instructions much better. `llama3.1:8b` works; `qwen2.5:14b`,
`llama3.3:70b` or similar are noticeably smarter if your PC can run them.
The mod uses Ollama's native API with an 8k context window (`ollamaContextSize`).

**LM Studio / llama.cpp server / any OpenAI-compatible server:**
```
/companion config provider lmstudio          (http://localhost:1234/v1)
/companion config provider llamacpp          (http://localhost:8080/v1)
/companion config url http://192.168.1.20:8000/v1   (anything else)
/companion config model <model-name>
```

**Groq models:** default is `llama-3.3-70b-versatile`. If you hit the free-tier rate limits, try
`/companion config model llama-3.1-8b-instant` or any other model listed in the Groq console.
`/companion config test` checks that the connection works.

You can also set the key through the `GROQ_API_KEY` environment variable instead of the config file.

> **Survival tip:** like a new player, a freshly summoned companion has an empty inventory. Toss it
> blocks, tools and food (press Q while looking at it, or right-click it to open its inventory), or ask it
> to gather what it needs ("get some wood and make a pickaxe").

## How it plays like a player

- Player model and skin (wide or slim arms), armor, held items, crouching, arm swings, player
  sounds, "Steve joined the game" / "left the game" messages, death messages.
- Walks and sprints with pathfinding, opens doors, swims, jumps up blocks, crouch-spams back
  at you when you crouch at it.
- **Survival:** breaks blocks at real speed with the right tool (crack animation, tool wear, proper drops),
  places blocks from its inventory, pillars up with dirt/cobble to reach high spots and digs back
  down, gets hungry and eats, drops its items when it dies and respawns next to you.
- **Creative:** unlimited blocks, instant breaking, can't be hurt, flies (and flies along when you fly).
  By default it copies your game mode (`/companion mode Steve auto|survival|creative`).
- Combat: picks its best weapon, jump-crits, backs off from hissing creepers, retreats to eat
  when low. Hostile mobs target it like a player. Stances: `passive`, `defensive` (default: fights
  back and defends you) and `aggressive`.
- Keeps working when you walk away (keeps its own chunks loaded), follows you through portals,
  teleports to you if you get very far away (configurable).

## Memory

Each companion has a memory file in `<world>/aicompanion/<name>.json`:
- facts it decided to remember (your name, preferences, promises...),
- saved places ("home", "the mine"),
- a rolling summary of older conversations (written by the model),
- recent chat and events (what it built, deaths, gifts, fights),
- its items while it's away.

It survives restarts, deaths and dismissals. `/companion memory Steve` shows it,
`/companion forget Steve [chat|facts|places|all]` clears it.

## Commands

| Command | |
|---|---|
| `/companion summon <name> [skin]` | Summon (or call over) a companion |
| `/companion dismiss <name>` | Send it away (keeps memory + items) |
| `/companion delete <name> confirm` | Delete it and all its memories |
| `/companion list` | All companions |
| `/companion status <name>` | Health, hunger, mode and what it's doing right now |
| `/companion say <name> <msg>` | Talk to it without using public chat |
| `/companion stop / follow / stay / come / tp <name>` | Quick orders without the AI |
| `/companion do <name> <json>` | Run actions directly, e.g. `{"type":"build","structure":"house","material":"spruce_planks"}` |
| `/companion mode <name> auto\|survival\|creative` | Game mode |
| `/companion stance <name> passive\|defensive\|aggressive` | Combat behaviour |
| `/companion skin <name> <player>` | Use a real player's skin |
| `/companion personality <name> <text>` | e.g. "grumpy dwarf who loves mining" |
| `/companion trust <name> <player>` | Let a friend give it orders too |
| `/companion memory <name>` / `forget <name> [what]` | Inspect / clear memory |
| `/companion inventory <name>` | Open its inventory (or right-click it) |
| `/companion config ...` | `provider`, `model`, `key`, `url`, `set <field> <value>`, `test`, `reload` |

`/ai` is an alias of `/companion`. Sneak + right-click the companion to toggle follow/stay.

## Configuration

`config/aicompanion.json` (created on first launch). Everything can also be changed with
`/companion config set <field> <value>`. Highlights:

| Setting | Default | |
|---|---|---|
| `provider` | `groq` | `groq`, `ollama`, `lmstudio`, `llamacpp`, `openrouter`, `openai` |
| `model` | *(provider default)* | |
| `listenRadius` | 48 | How far away it hears you |
| `respondWithoutName` | true | Answer the owner without being named |
| `typingDelay` | true | Replies appear after a human-like typing pause |
| `idleChatter` / `idleChatterMinutes` | true / 6 | Occasionally says something on its own |
| `reactToEvents` | true | Reacts to finished tasks, gifts, getting hurt... |
| `requireMaterialsInSurvival` | true | Survival builds need real blocks |
| `leaveWithOwner` | true | Leaves/joins together with you |
| `teleportToOwnerDistance` | 64 | 0 = never teleport, walk only |
| `mobsTargetCompanion` | true | Hostile mobs attack it like a player |
| `maxBuildBlocks` | 6000 | Safety limit per build |

## Building from source

Requires Java 21.
```
./gradlew build          # jar in build/libs/
./gradlew runGametest    # headless server tests: building, shelters, mining, crafting, action parsing
```
Every push is built and tested by GitHub Actions; the jar is attached to each run as an artifact.
