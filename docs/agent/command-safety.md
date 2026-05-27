# Command Safety Rules

Unless the user explicitly requests otherwise, follow the rules below before executing any shell command.

---

## Absolutely Forbidden Commands

These commands must never be executed without an explicit user request.

| Command | Reason |
|---------|--------|
| `git commit` | Only the user has commit authority |
| `git push` | Only the user has push authority |
| `git reset --hard` | Permanently discards uncommitted work |
| `git rebase -i` | Destructively rewrites commit history |
| `rm -rf [project directory]` | Irreversible deletion |
| `kubectl delete pod/deployment/service/...` | Destroys production resources |
| `kubectl apply -f` (production environment) | Risk of unintended deployment |
| `docker volume rm` | Permanently deletes volume data |
| `docker compose down -v` | Removes all containers and volumes |
| `npm audit fix --force` | May introduce breaking dependency changes |
| Destructive SQL (`DROP TABLE`, `TRUNCATE`) | Causes data loss |
| Modifying secrets, certificates, or production config | Breaks security and operational stability |

---

## Proceed with Caution

Confirm the scope before running these commands.

| Command | What to verify |
|---------|----------------|
| `./gradlew clean build` | Check for build failures and downstream impact |
| `npm install` / `npm ci` | Review package.json changes; watch for lock-file conflicts |
| `docker compose up` | Check for port conflicts with existing containers |
| `kubectl apply -f` (dev / staging) | Review the manifest content before applying |
| `npm run sync` (desktop) | Ensure nodejs-frontend changes are complete first |

---

## Long-Running Server Commands

Run these only when the user **explicitly requests runtime verification**.

| Command | Description |
|---------|-------------|
| `npm run start` / `npm run dev` | Starts the Node.js server |
| `java -jar build/libs/*.jar` | Starts the Spring Boot server |
| `npm run dev` (chatforyou-desktop) | Launches the Electron app |
| `npm run start` (chatforyou-desktop) | Launches the Electron app |

---

## Related Rules
- `AGENT_GUIDE.md` — Non-Negotiable Safety Boundaries
- `AGENT_GUIDE.md` — Required Agent Reference Documents
