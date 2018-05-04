raven: clojure sentry client library
====================================

A Clojure library to send events to a sentry host.


### Usage

```clojure
[[spootnik/raven "0.1.4"]]
```

The main exported function is `capture!` and has two arities:

- `(capture! dsn event)`: Send a capture over the network, see the description of DSN and ev below.
- `(capture! context dsn event)`: Send a capture passing additional context, such as a specific HTTP client.

#### Arguments

- **DSN**: A Sentry DSN as defined http://sentry.readthedocs.org/en/2.9.0/client/index.html#parsing-the-dsn
- **Event**: Either an exception or a map.
<<<<<<< HEAD
- **Context**: A map of additional information you can pass to Sentry. Note
=======
- **Context**: A map of aditional informations you can pass to Sentry. Note
>>>>>>> Add support for explicit context.
  that omitting this parameter will make use of some thread-local storage for
  some of the functionality.

#### Passing your own http instance

In many cases, it makes sense to reuse an already existing http client (created
with http/build-client). Raven will reuse an http instance if it is passed to
the (capture!) function through the `context` parameter, as :http.

```clojure
(capture! {:http (http/build-client {})} "<dsn>" "My message")
```

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

#### User

Sentry supports adding information about the user when capturing events. This
library makes it possible using the `add-user!` function, with the following
arities:

- `(add-user! user)` Store a user in thread-local storage.
- `(add-user! context user)` Store a user in a user-specified context. Context
  is expected to be map-like.

Well-formatted user maps can be created with the `make-user` helper function,
with the following arities:

- `(make-user id)` A simple user map with the only required field (the user's
  id) is created.
- `(make-user id email ip-address username)` A map with all "special"
  fields recognised by sentry is created. Additional fields can be added to the
  created user map if desired, and will simply show up in the interface as
  extra fields.

More information can be found on [Sentry's documentation website](https://docs.sentry.io/clientdev/interfaces/user/)

#### Full example

```clojure
(def dsn "https://098f6bcd4621d373cade4e832627b4f6:ad0234829205b9033196ba818f7a872b@sentry.example.com/42")
(add-breadcrumb! (make-breadcrumb! "The user did something" "com.example.Foo"))
(add-breadcrumb! (make-breadcrumb! "The user did something wrong" "com.example.Foo" "error"))
(add-user (make-user "user-id" "test@example.com" "127.0.0.1" "username"))
(capture! dsn (Exception.))
```

### Changelog

#### unreleased

- Added support for User interface
- Added support for Breadcrumbs interface
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
