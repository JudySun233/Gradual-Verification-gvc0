# Weaver
The `weaver` translates the checks into executable C0 code, ensuring the program adheres to specified behaviors during execution.

## Checker
The `checker` identifies necessary runtime checks, determines their appropriate insertion points within the program, and translates these checks into executable code. 

- **`CheckerMethod` class**: a container for managing temporary variables and the result variables needed for runtime verification.
- **`insert` method**: it inserts runtime checks into the program based on the collected verification data. It prepares the runtime environment by adding necessary fields and initializing structures; it orchestrates the process of integrating runtime checks into the appropriate locations within the program.
- **`insertAt` method**: it handles various locations within the program, such as loop starts/ends, method preconditions, and postconditions, and inserts the runtime checks accordingly.
- **condition handling functions**:
  - `foldConditionList`: simplifies a list of conditions into a single expression using a specified binary operation (e.g., logical AND, OR).
  - `getCondition`: translates a `Condition` instance into an executable IR expression, enabling complex logical conditions to be evaluated at runtime.

## Collector
The `collector` gathers and organizes the necessary information for runtime check insertion based on analyzing both the intermediate representation (IR) of the program and the static verification results.
