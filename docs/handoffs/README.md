# Feature wiring handoffs

Parallel feature agents place exact shared-root requests here as `<FEATURE>_WIRING_REQUEST.md`.
The integration owner applies them after reviewing the feature branch and contract. A request must
name routes, palette entries, Page mappings, action/repository additions, migration reservation and
tests; it must not ask the integrator to infer behavior from a diff.
