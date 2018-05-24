# TODO

There are a few things we could add to this library, or general ideas that could
prove useful.
In no particular order of importance:

- Refactor the source code in several clojure files
- Use aleph instead of net (in order to allow injecting an aleph connection pool
  instead of a net/client)
- Offer a top-level BYON (bring your own networking) function, returning a well
  formatted ring client request map.
- Offer a top level function to add an exception+stacktrace to a context map
  like `(add-exception! context throwable)`. This allows us to compose functions
  better.
