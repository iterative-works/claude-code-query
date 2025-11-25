# Development Tools

## jvm-proxy

A local HTTP proxy that enables JVM tools (sbt, scala-cli, coursier, maven, gradle) 
to work in environments with authenticated HTTP proxies.

### Problem

JVM tools often fail to authenticate with HTTP proxies that require Basic auth for 
HTTPS tunneling (CONNECT method). This is common in corporate environments and 
sandboxed containers.

### Solution

`jvm-proxy` runs a local proxy on localhost that:
1. Accepts unauthenticated connections from JVM tools
2. Forwards requests to the upstream proxy with proper authentication
3. Handles HTTPS CONNECT tunneling correctly

### Usage

```bash
# Start the proxy in background
./tools/jvm-proxy start &

# Set up Java to use the local proxy
eval $(./tools/jvm-proxy env)

# Now run JVM tools normally
sbt compile
scala-cli compile .
```

### Commands

| Command | Description |
|---------|-------------|
| `jvm-proxy start` | Start the proxy (foreground) |
| `jvm-proxy stop` | Stop the proxy |
| `jvm-proxy status` | Check if running |
| `jvm-proxy env` | Print JAVA_TOOL_OPTIONS export |

### Requirements

- Python 3.6+
- `HTTP_PROXY` or `HTTPS_PROXY` environment variable set with credentials
