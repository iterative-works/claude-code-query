# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an unofficial Scala SDK for Claude Code, designed as a thin wrapper around the Claude Code CLI. It follows the Python SDK architecture but adapted for idiomatic Scala with functional programming patterns.

## Build and Development Commands

- **Compile**: `./mill __.compile`
- **Test**: `./mill __.test`
- **Run Single Test**: `./mill direct.test` or `./mill effectful.test`
- **Publish to local Maven**: `./mill __.publishLocal`

The project uses Mill as the build tool with three modules: `core`, `direct`, and `effectful`.

## Architecture

For detailed architecture information, see [ARCHITECTURE.md](ARCHITECTURE.md).
