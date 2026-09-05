# gsuif-backend

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

## Git Workflow

All development work must follow the branching workflow below:

1. Create a feature branch: `feature/SCRUM-XX-description`
2. Submit a Pull Request to `develop`
3. After review and approval, merge into `develop`
4. Submit a Pull Request from `develop` to `main`
5. After review and approval, merge into `main`

### Branches

- `main`: Stable branch containing production-ready code.
- `develop`: Integration branch for completed features.
- `feature/SCRUM-XX-description`: Feature or task-specific development branch.

### Pull Requests

- Direct pushes to `main` and `develop` are not allowed.
- All changes must be submitted through Pull Requests.
- Pull Requests require at least one approving review before merging.
- CI checks must pass before merging once the CI pipeline is configured.

### Commit Convention

Use the following format for commit messages:

`[SCRUM-XX] Short description`

Example:

`[SCRUM-17] Add project gitignore`
