# TODO

There are a few things we could add to this library, or general ideas that could
prove useful.
In no particular order of importance:

- Refactor the source code in several clojure files
- Offer a top-level BYON (bring your own networking) function, returning a well
  formatted ring client request map.
- Allows passing an aleph connection pool to use via context (instead of
  defaulting to aleph's default connection pool).
