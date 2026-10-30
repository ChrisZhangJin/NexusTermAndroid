---
name: orchestrator-mode
description: On this project the user wants Claude to act as an orchestrator and dispatch subtasks to agents
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 323d9a20-4dfb-4370-95a6-f6b681a66926
  modified: 2026-10-30T02:31:42.516Z
---

The user's standing instruction for this project:

> 你作为让任务的 orchestrator，把每个子任务派发给 agent 去执行，你只负责调度和结果检查

So: break the work into subtasks, hand each to a subagent, and keep the main
thread for scheduling and reviewing what comes back — don't do the
implementation inline.

**Why:** keeps the main context free for judgement and cross-checking instead of
filling it with file dumps and build logs, which is what let the earlier
multi-round feature work finish in one session.

**How to apply:** parallelise only where the agents' file sets are disjoint.
Device work must be **strictly sequential** — there is one USB-attached phone at
a time and two agents driving `adb` will corrupt each other's test runs. Review
agent conclusions rather than accepting them: several have been wrong or
over-confident, and one judgement call (a density-independent pinch threshold)
had to be overridden. Trivial one-shot lookups (`adb devices`, reading one file)
are still fine to do directly. See [[test-devices]].
