raven: clojure sentry client library
====================================

A Clojure library to send events to a sentry host.


### Usage

```clojure
[[spootnik/raven "0.1.2"]]
```

The main exported function is `capture` and has two arities:

- `(capture dsn event)`: Send a capture over the network, see the description of DSN and ev below.
- `(capture client dsn event)`: Use the provided http client (as built by `net.http.client/http-client` from https://github.com/pyr/net.

#### Arguments

**DSN**: A Sentry DSN as defined http://sentry.readthedocs.org/en/2.9.0/client/index.html#parsing-the-dsn
**Event**: Either an exception or a map

### Changelog

#### 0.1.2

- Prevent reflection
- Support `ex-data`

### Notes

Largely inspired by https://github.com/sethtrain/raven-clj

### License

Copyright © 2016 Pierre-Yves Ritschard <pyr@spootnik.org>

Distributed under the MIT/ISC License
