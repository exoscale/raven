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

- `(add-breadcrumb! message category)` The created breadcrumb has a level of
  "info"
- `(add-breadcrumb! message category level)` Allows you to specify the desired
  breadcrumb "level". Level can be one of: `["debug" "info" "warning" "warn" "error" "exception" "critical" "fatal"]`
- `(add-breadcrumb! message category level timestamp)` Allows you to pass in a
  specific timestamp for the breadcrumb you are creating.

Please note that the breadcrums use thread-local storage, and therefore might
be ill-suited for some use cases.

More information can be found on [Sentry's documentation website](https://docs.sentry.io/clientdev/interfaces/breadcrumbs/)

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
