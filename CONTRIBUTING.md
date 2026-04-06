# Contributing

## CI Checks

All pull requests are validated by GitHub Actions CI. The workflow runs the following checks:

### Parallel Checks (run immediately)

| Job | Command | Description |
|-----|---------|-------------|
| **Compile** | `./mill __.compile` | Compiles all modules |
| **Format** | `./mill __.checkFormat` | Validates Scalafmt formatting |
| **Lint** | `./mill __.fix --check` | Validates Scalafix FP rules |

### Sequential Check (runs after compile)

| Job | Command | Description |
|-----|---------|-------------|
| **Test** | `./mill __.test` | Runs all unit tests |

## Git Hooks

The project provides optional git hooks to catch issues before they reach CI.

### Installation

```bash
git config core.hooksPath .git-hooks
```

This points git at the `.git-hooks/` directory in the repository root.
It works with worktrees and doesn't require symlinks.

### What the Hooks Do

| Hook | Trigger | What it checks | Time |
|------|---------|----------------|------|
| **pre-commit** | Before each commit | Scala formatting | ~10 seconds |
| **pre-push** | Before each push | Compilation warnings, then all unit tests | ~3 minutes |

## Local Development

Before pushing, you can run the same checks CI will run:

```bash
# Check formatting
./mill __.checkFormat

# Fix formatting automatically
./mill __.reformat

# Check Scalafix rules
./mill __.fix --check

# Apply Scalafix fixes
./mill __.fix

# Run all tests
./mill __.test
```

## Troubleshooting

### "Code formatting issues detected"

Your code doesn't match the project's Scalafmt configuration.

**Fix:**
```bash
./mill __.reformat
git add -u
```

### "Compilation warnings found"

The pre-push hook enforces warning-free compilation. All warnings must be
resolved before pushing.

**Fix:**
```bash
# See all warnings
./mill clean __.compile && ./mill __.compile

# Common causes: unused imports, unused parameters, unused values
# Remove the unused code or suppress with @nowarn if justified
```

### "Tests are failing"

One or more tests are broken.

**Fix:**
```bash
# Run tests to see failures
./mill __.test

# Run tests for a specific module
./mill direct.test
```

### "Scalafix violations detected"

Your code violates FP rules (e.g., using `null`, `var`, `throw`).

**Fix:**
```bash
# See what rules are violated
./mill __.fix --check

# If the violation is intentional (e.g., Java interop), add a suppression:
// scalafix:off DisableSyntax.null
val result = javaApi.mayReturnNull()  // Java API returns null
// scalafix:on DisableSyntax.null
```

### Hook not running

Ensure hooks are installed and executable:

```bash
git config core.hooksPath .git-hooks
chmod +x .git-hooks/pre-commit .git-hooks/pre-push
```

### Format failures after merge

After merging or rebasing, formatting may need to be re-applied:

```bash
./mill __.reformat
git add -u
git commit --amend --no-edit
```

### Bypassing Hooks (Emergency Only)

In rare cases, you may need to bypass hooks:

```bash
# Bypass pre-commit hook
git commit --no-verify

# Bypass pre-push hook
git push --no-verify
```

**Warning:** Use only for genuine emergencies. CI will still run all checks,
and your PR will fail if there are issues.
