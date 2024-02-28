# Weaver
The `weaver` translates the checks into executable C0 code, ensuring the program adheres to specified behaviors during execution.

## Checker
The `checker` identifies necessary runtime checks, determines their appropriate insertion points within the program, and translates these checks into executable code. 

- **`CheckerMethod` class**:  
  a container for managing temporary variables and the result variables needed for runtime verification.
- **`insert` method**:  
  it inserts runtime checks into the program based on the collected verification data. It prepares the runtime environment by adding necessary fields and initializing structures; it orchestrates the process of integrating runtime checks into the appropriate locations within the program.
- **`insertAt` method**:  
  it handles various locations within the program, such as loop starts/ends, method preconditions, and postconditions, and inserts the runtime checks accordingly.
- **condition handling functions**:  
  - `foldConditionList`: simplifies a list of conditions into a single expression using a specified binary operation (e.g., logical AND, OR).
  - `getCondition`: translates a `Condition` instance into an executable IR expression, enabling complex logical conditions to be evaluated at runtime.

## Check
The `check` defines the structures and mechanisms for representing  field accesses, predicate conditions, and logical expressions as checks that can be dynamically verified within the target program. 

- **`Check` trait**:  
  it serves as the base trait for all types of checks that can be performed, including permission checks, separation checks, and accessibility checks.
- **`Check` object**:  
  it provides a fromViper method that converts Viper expressions into GVC0 check expressions, enabling the transformation of static verification results into runtime verifiable checks.  
  
## Collector
The `collector` gathers and organizes the necessary information for runtime check insertion based on analyzing both the intermediate representation (IR) of the program and the static verification results.

- **`Location` trait**:  
  it represents various points within a program where runtime checks might be inserted, such as before or after an operation (`Pre`, `Post`), at the start or end of a loop (`LoopStart`, `LoopEnd`), or at the beginning or end of a method (`MethodPre`, `MethodPost`).
- **`Condition` trait**:  
  it abstracts the different types of conditions that can influence whether a runtime check should be executed, including logical operations (`NotCondition`, `AndCondition`, `OrCondition`) and immediate conditions derived from expressions (`ImmediateCondition`).
- **`CheckInfo` class**:  
  it encapsulates a runtime check along with an optional condition under which the check is relevant.
- **`RuntimeCheck` class**:  
  it is similar to `CheckInfo`, but tied to a `Location` within the program, indicating precisely where and under what conditions a runtime check should be inserted.
- **`CallStyle` enumeration**:  
  it defines the verification strategy or "style" to be applied to a method call, ranging from fully precise checks (`PreciseCallStyle`), checks that are precise only in preconditions (`PrecisePreCallStyle`), to fully imprecise checks (`ImpreciseCallStyle`), and a special style for the main method (`MainCallStyle`).
- **`CollectedMethod` class**:  
  it aggregates all information necessary for inserting runtime checks into a single method, including conditions, checks, return points, calls, allocations, and the method's call style. It also tracks whether the method's body contains imprecision that might affect the verification.
- **`CollectedProgram` class**:  
  it represents the entire program with its methods (`CollectedMethod` instances), temporary variables used for verification, and a mapping to the original IR methods. It serves as the final output of the `Collector`, encapsulating all data required for runtime check insertion.
- **`collect` function**:  
  it takes the program's IR and the Viper static verification results as inputs, processes them to gather verification information, and outputs a `CollectedProgram` instance.

## SpecificationContext
It provides a structured framework for transforming and adapting expressions based on the context in which they are used.

- **`ValueContext` object**:  
  it implements `SpecificationContext` for validating those invalid expressions, like `\result`, are not misused outside of their intended context.
- **`PredicateContext` class**:  
  it customizes `SpecificationContext` for use within predicate definitions, mapping predicate parameters to their actual values or throwing exceptions for undefined variables or misuse of `\result`.
- **`ReturnContext` class**:  
  it directly returns the provided `IR.Expression` when `convertResult` is called, while simply passing through any variables unchanged.
- **`CallSiteContext` class**:  
  it adapts `SpecificationContext` for method call sites, replacing parameters with their actual argument values at the point of invocation and ensuring correct handling of `\result`.
