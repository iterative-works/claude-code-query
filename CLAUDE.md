# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an unofficial Scala SDK for Claude Code, designed as a thin wrapper around the Claude Code CLI. It follows the Python SDK architecture but adapted for idiomatic Scala with functional programming patterns.

## Build and Development Commands

- **Compile**: `scala-cli compile .`
- **Run**: `scala-cli run .` 
- **REPL**: `scala-cli repl .`
- **Package**: `scala-cli package .`
- **Run Single Test**: `scala-cli test . --test-only '*packageorclassnamepart*' -- '*test name*'`

The project uses Scala CLI as the build tool with dependencies managed in `project.scala`.

## Architecture

For detailed architecture information, see [ARCHITECTURE.md](ARCHITECTURE.md).
