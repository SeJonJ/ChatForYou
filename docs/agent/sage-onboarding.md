# SAGE Team Onboarding

Host: `codex`
Selected Codex CORE skill scope: `project-local`

Project-local CORE skills live under `.codex/skills`. When these files are committed, a teammate can discover the prompts after cloning the repository.
This repository content does not install the `sage` or `sage-hook` executable. Each teammate still installs the SAGE CLI/runtime separately and runs `sage doctor`.

Run `sage-init` only for the first shared+local bootstrap. When the shared profile is already bootstrapped, each teammate runs `sage-init-local` to create only `sage/project-profile.local.yaml`.

Do not keep duplicate global, `.codex/skills`, and `.agents/skills` copies of the same `$sage-*` CORE skill. Run `sage doctor` after installation; the manifest receipt records intent, while host precedence is treated as ambiguous when duplicate copies exist.
