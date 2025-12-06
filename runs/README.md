# Runs

Runs are single files in `runs/<run-id>.md` containing an ordered list of tasks that follow the Task Schema in `docs/protocol.md`. A run should be defined as fully as possible before execution.

Parallel safety is defined at task creation: each task lists exclusive capabilities, shared/read-only capabilities, and sequencing constraints. Agents only start tasks whose exclusive capabilities do not overlap with other in-progress tasks in the run file.

Avoid adding tasks mid-run unless a blocker makes it impossible to proceed; prefer queuing follow-ups for the next run. When a run is complete, archive the file (e.g., move to `runs/archive/`) and start a new run file for subsequent work.
