# Axiora product record

<!-- impeccable:product-schema 1 -->

## Platform

android

The intended learning application is Android. The present deliverable is a static documentation website and Markdown design, not application UI.

## Stack

Delegated by the owner for this design. Proposed application: Kotlin, Jetpack Compose, SQLite, and a Kotlin/JVM compiler CLI. Documentation: static HTML/CSS/JavaScript generated from Markdown using Python. Application dependencies and minimum Android version remain unselected.

## Users

Design assumption: mathematically motivated adults studying proof-based undergraduate or early graduate mathematics independently. The first course slice concerns prime and maximal ideals. Classroom administration and young-child instruction are outside the initial scope.

## Product Purpose

Develop independent mathematical reasoning through structured practice, proof attempts, feedback, and review. The owner permits rethinking the original discussion; the detailed proposal lives in `docs/markdown/`.

## Operating Context

Offline study on Android, short recall sessions and longer proof sessions. Authors prepare content separately on a computer. No app, users, learning outcomes, benchmarks, or production integrations are established yet.

## Capabilities and Constraints

Proposed: declarative content packages, deterministic structured evaluation, learner-reviewed proofs, local progress, versioned imports. AI generation and tutoring are optional later additions. The handbook must be readable without network access.

## Product Principles

- Independent reasoning is the goal; completion is only navigation state.
- Report the strength and origin of evidence honestly.
- Keep content and learner history independent.
- Start with a complete small learning loop before broad automation.

## Brand Commitments

Axiora is the working name, drawn from the discussion and repository name. Availability and final ownership have not been verified. No logo or Android visual system has been approved.
