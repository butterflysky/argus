# Commands

(Admin commands are Discord slash commands; responses are ephemeral to the caller.)

- `/whitelist add player:<mc|uuid> [discord:user] [mcname]` – grant access (+ optional link)
- `/whitelist remove player:<mc|uuid> [discord:user]` – revoke access
- `/whitelist status player:<mc|uuid>` – show access/ban state
- `/whitelist apply mcname:<name>` – user submits application (Mojang-validated)
- `/whitelist list-applications` – paged list with approve/deny buttons
- `/whitelist approve|deny application:<id> [reason]`
- `/whitelist warn player:<...> reason:<text>`
- `/whitelist ban player:<...> reason:<text> [duration_minutes]`
- `/whitelist unban player:<...>`
- `/whitelist comment player:<...> note:<text>` (admin-only visibility)
- `/whitelist review player:<...>` – history view (comments hidden from users)
- `/whitelist my` – user sees their own warnings/ban
- `/whitelist help` – quick reference

Linking
- `/link <token>` – users consume tokens issued by the server/login flow.
