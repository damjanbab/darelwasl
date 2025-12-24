# Runs

Runs are single files in `runs/<run-id>.md` containing an ordered list of tasks. A run should be defined as fully as possible before execution.

Parallel safety is defined at task creation: each task lists exclusive capabilities, shared/read-only capabilities, and sequencing constraints. Agents only start tasks whose exclusive capabilities do not overlap with other in-progress tasks in the run file.

Avoid adding tasks mid-run unless a blocker makes it impossible to proceed; prefer queuing follow-ups for the next run. Each run works on its own branch (`run/<run-id>`); task work happens on sub-branches (`run/<run-id>/<task-id>`) with PRs into the run branch. When a run is complete, merge the run branch to `main` via PR only after a manual approval/green-signal from the product owner, archive the run file (e.g., move to `runs/archive/`), and start a new run file for subsequent work.
