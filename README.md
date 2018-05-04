raven: clojure sentry client library
====================================

A Clojure library to send events to a sentry host.


### Usage

```clojure
[[spootnik/raven "0.1.4"]]
```

The main exported function is `capture!` and has two arities:

- `(capture! dsn event)`: Send a capture over the network, see the description of DSN and ev below.
- `(capture! client dsn event)`: Use the provided http client (as built by `net.http.client/http-client` from https://github.com/pyr/net).

#### Arguments

- **DSN**: A Sentry DSN as defined http://sentry.readthedocs.org/en/2.9.0/client/index.html#parsing-the-dsn
- **Event**: Either an exception or a map

#### Breadcrumbs

Adding sentry "breadcrumbs" can be done using the `add-breadcrumb!` function,
that has the following arities:

- `(add-breadcrumb! breadcrumb)` Store a breadcrumb in thread-local storage.
- `(add-breadcrumb! context breadcrumb)` Store a breadcrumb in a user-specified
context. Context is expected to be map-like.

Well-formatted breadcrumb maps can be created with the `make-breadcrumb!`
helper, with the following arities:

- `(make-breadcrumb! message category)` A breadcrumb will be created with the
  "info" level.
- `(make-breadcrumb! message category level)` This allows specifying a level.
  Levels can be one of: 'debug' 'info' 'warning' 'warn' 'error' 'exception' 'critical' 'fatal'
- `(make-breadcrumb! message category level timestamp)` This allows setting a
  custom timestamp instead of letting the helper get one for you. Timestamp
  must be a floating point representation of **seconds** elapsed since the
  epoch (not milliseconds).

More information can be found on [Sentry's documentation website](https://docs.sentry.io/clientdev/interfaces/breadcrumbs/)

#### Full example

```clojure
(def dsn "https://098f6bcd4621d373cade4e832627b4f6:ad0234829205b9033196ba818f7a872b@sentry.example.com/42")
(add-breadcrumb! (make-breadcrumb! "The user did something" "com.example.Foo"))
(add-breadcrumb! (make-breadcrumb! "The user did something wrong" "com.example.Foo" "error"))
(capture! dsn (Exception.))
```

### Changelog

#### unreleased

- Added support for breadcrumbs
- Added specs for wire format (JSON)
- Code cleanup

#### 0.1.4

- Add deps.edn support
- Adapt to recent versions of net


#### 0.1.2

- Prevent reflection
- Support `ex-data`

### Notes

Largely inspired by https://github.com/sethtrain/raven-clj

### License

Copyright © 2016 Pierre-Yves Ritschard <pyr@spootnik.org>

Distributed under the MIT/ISC License
