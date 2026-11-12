---
name: planning
description: Describes the required approach and instructions what the User expects as an outcome of a Planning sessions. Use when the user ask "prepare a plan" or "prepare the tasks (task lists) for implementation". 
---

## Planning
- **plan content** - When the user asks you to plan, make sure these artefacts are generated:
    - architecture - the description of the solution architecture, component diagrams, flow diagrams, tech stack
	- contracts - all the APIs (public or internal), all data structures and DTOs, all json / yaml schemas, described in detail
	- plan - phased implementation plan: overal plan, phases with clear deliverables, pre-flight conditions, definitions of DONE

These artefacts MUST exist before the task lists are generated.

## Task Lists
- **task lists** - When the user asks you to create a task list (or several), according to the plan, follow these instructions
    - be extremely specific. The coding agents and junior developers MUST have very clear instructions. Do not leave anything to their imagination
	- provide instructions and references how to use complex libraries. Use `context7` mcp server to get information about latest APIs and provide that in the task list (see below) as examples. We have a few complex libraries cloned to disk, this is another source of info, see below.
	- the coding agent and junior developers have issues following a long task list. Break the whole structure (Plan - Phase - Stage - (Section)) into separate task lists of 6-8 tasks max. Create an overall task management document referencing and structuring these mini-task-list. Use checkboxes everywhere so we can track the progress. Instruct the coder to always check each and every checkbox right after the task is done
	- use TDD: for a Phase (or Stage) prepare the tasks so that tests are created first. Define the tests yourself. Make sure the "elementary" unit tests are prepared, as well as component tests (inter-class, inter-object). Do not plan for full E2E integration tests, integration testing has a separate flow.
	
## Examples
In our codebase, we have already quite a number of examples. Here is a table with the examples to be referenced for specific cases:
- Ktor setup
- Kotlin (Ktor) serialization
- MCP Server in kotlin using Kotlin MCP SDK (especially the http streaming)
- Python + LangGraph agent 
- Frontend in TS + Vue
- Apache Calcite and SQL and RelNodes

See the file `EXAMPLES.md`; it references the `ai-platform` project at `~/Dev/ai-platform`

## Locally Cloned libraries 

These libraries are cloned to disk and `graphify` was called so the `graphify-out` folder exists. Use them for looking how a library internally works and what are the APIs to be used.

Apache Calcite: `~/Dev/view-only/calcite`
JetBrains Koog: `~/Dev/view-only/koog`
Kotlin MCP SDK: `~/Dev/view-only/kotlin-mcp-sdk`