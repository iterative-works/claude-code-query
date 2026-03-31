# Phase 01: Build Infrastructure — Tasks

**Issue:** CC-12
**Phase goal:** Create Mill multi-module build (`build.mill` + `.mill-version`) that defines `core`, `direct`, and `effectful` modules with correct dependency declarations.

---

## Setup

- [x] `[setup]` Create `.mill-version` with content `1.1.2`
- [x] `[setup]` Create `build.mill` skeleton: import `mill._`, import `mill.scalalib._`, import `mill.scalalib.publish._`, import `IWScalaModule` from `mill-iw-support`
- [x] `[setup]` Define shared trait (e.g. `trait SharedModule extends IWScalaModule with PublishModule`) with:
  - `scalaVersion` = `IWScalaVersions.scala3LTSVersion` (3.3.7)
  - `pomSettings` — org `works.iterative`, Apache-2.0 license, GitHub VCS URL
  - `publishVersion` = `"0.1.0-SNAPSHOT"`

## Core Module

- [x] `[impl]` Define `object core extends SharedModule` with `ivyDeps`:
  - `io.circe::circe-core:0.14.15`
  - `io.circe::circe-parser:0.14.15`
- [x] `[impl]` Add `object test extends ScalaTests with TestModule.Munit` inside `core` with test deps:
  - `org.scalameta::munit:1.1.1`
  - `org.scalacheck::scalacheck:1.18.1`
  - `org.scalameta::munit-scalacheck:1.1.0`

## Direct Module

- [x] `[impl]` Define `object direct extends SharedModule` with `moduleDeps = Seq(core)` and `ivyDeps`:
  - `com.lihaoyi::os-lib:0.11.8`
  - `com.softwaremill.ox::core:1.0.4`
  - `org.slf4j:slf4j-api:2.0.17` (compile-scope logging API)
- [x] `[impl]` Add `object test extends ScalaTests with TestModule.Munit` inside `direct` with test deps:
  - `org.scalameta::munit:1.1.1`
  - `ch.qos.logback:logback-classic:1.5.32` (test-scoped runtime backend)

## Effectful Module

- [x] `[impl]` Define `object effectful extends SharedModule` with `moduleDeps = Seq(core)` and `ivyDeps`:
  - `org.typelevel::cats-effect:3.7.0`
  - `co.fs2::fs2-core:3.13.0`
  - `co.fs2::fs2-io:3.13.0`
  - `org.typelevel::log4cats-slf4j:2.8.0`
- [x] `[impl]` Add `object test extends ScalaTests with TestModule.Munit` inside `effectful` with test deps:
  - `org.scalameta::munit:1.1.1`
  - `org.typelevel::munit-cats-effect:2.1.0`
  - `org.typelevel::log4cats-testing:2.7.1`
  - `ch.qos.logback:logback-classic:1.5.32` (test-scoped runtime backend)

## Verification

- [x] `[verify]` Run `mill resolve _` — confirm Mill recognizes the build and all three modules appear
- [x] `[verify]` Run `mill show core.moduleDeps` — confirm empty (no upstream deps)
- [x] `[verify]` Run `mill show direct.moduleDeps` — confirm depends on `core`
- [x] `[verify]` Run `mill show effectful.moduleDeps` — confirm depends on `core`
- [x] `[verify]` Spot-check `mill show core.ivyDeps` — confirm circe deps, no ox/cats-effect
- [x] `[verify]` Spot-check `mill show direct.ivyDeps` — confirm os-lib + ox, no cats-effect
- [x] `[verify]` Spot-check `mill show effectful.ivyDeps` — confirm cats-effect + fs2, no ox
- [x] `[verify]` Confirm `project.scala` and `publish-conf.scala` are NOT deleted (kept until later phases)

---

**Note:** Compilation (`mill core.compile` etc.) is expected to fail at this phase because sources have not been moved into Mill's layout yet. That happens in Phase 02.
