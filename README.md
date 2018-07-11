raven: clojure sentry client library
====================================

[![Travis status](https://api.travis-ci.org/exoscale/raven.svg)](https://travis-ci.org/exoscale/raven)

A Clojure library to send events to a sentry host.

![An exoscale Raven](raven.png "An Exoscale Raven")

### Usage

[![Clojars Project](https://img.shields.io/clojars/v/exoscale/raven.svg)](https://clojars.org/exoscale/raven)

The main exported function is `capture!` and has three arities:

- `(capture! dsn event)`: Send a capture over the network, see the description of DSN and ev below.
- `(capture! dsn event tags)`: Send a capture passing an extra map of tags.
- `(capture! context dsn event tags)`: Send a capture passing additional context, such as a specific HTTP client.

The `capture!` function returns the Sentry Event ID.

```clojure
(println "Sentry event created" (capture! <dsn> (Exception.)))
```

#### Arguments

- **DSN**: A Sentry DSN as defined http://sentry.readthedocs.org/en/2.9.0/client/index.html#parsing-the-dsn
- **Event**: Either an exception or a map.
- **Tags**: A map of extra information to be sent (as Sentry "tags").
- **Context**: A map of additional information you can pass to Sentry. Note
  that omitting this parameter will make use of some thread-local storage for
  some of the functionality.

#### Passing your own aleph connection pool

In many cases, it makes sense to reuse an already existing aleph conneciton pool (created
with http/connection-pool). Raven will reuse a connection pool if it is passed to
the (capture!) function through the `context` parameter, as `:pool`.

```clojure
(capture! {:pool (http/connection-pool {:connection-options {:raw-stream? true}})} "<dsn>" "My message")
```

#### More aleph options

You can also pass the following aleph configuration through the context:

```clojure
(capture! {:pool nil
           :middleware nil
           :pool-timeout nil
           :response-executor nil
           :request-timeout nil
           :read-timeout nil
           :connection-timeout nil})
```

### Extra interfaces

#### Tags

On top of being able to set tags at capture time, it is possible to add extra
tags using the `add-tag!` function, declaring the following arity:

- `(add-tag! tag value)` Adds a tag entry with the specified tag name and
  value to a thread-local storage.
- `(add-tag! context tag value)` Adds a specified tag in a user-specified
  context. Context is expected to be map-like.

Tags specified this way will be overwritten by tag specified as part of the
`(capture!)` call.

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

#### HTTP Requests

As for Users, Sentry supports adding information about the HTTP request that
resulted in the captured exception. To fill in that information this library
provides `add-ring-request!` and `add-full-ring-request!` functions,
with the following arities:

- `(add-ring-request! ring-request)` Extract the required information from the
    supplied ring-compatible request map, and store the result in thread-local
    storage for future delivery with a call to `(capture!)`
- `(add-ring-request! context ring-request)` Store the HTTP information
   extracted from the passed ring-compatible request in the user-specified
   map-like context (expected to be ultimately passed to `(capture!)`).

The `add-full-ring-request!` function provides the same arities, and includes
the request body in the capture, while `add-ring-request!` does not.

Well formatted HTTP information maps can otherwise be created with the
`make-http-info` helper function, with the following arities:

- `(make-http-info url method)` A simple HTTP info map with only required
  fields (the request's URL and method) is created.
- `(make-http-info url method headers query_string cookies data env)` Creates a
  map with all the "special" fields recognised by Sentry. Additional fields can
  be added to the created HTTP map if desired, and will simply show up in the
  interface as extra fields.

Those maps can be passed to the Sentry context using the `add-http-info!`
function, with the following arities:

- `(add-http-info! info)` Store the HTTP information in thread-local storage.
- `(add-http-info! context info)` Store the HTTP information in the
    user-specified map-like context (expected to be ultimately passed to `(capture!)`).

Example:

```clojure
(add-ring-request! ring-request)
(capture! <dsn> "First capture")
(add-http-info! (make-http-info "http://example.com" :get))
(capture! <dsn> "Second capture")
```

More information about the HTTP interface can be found on [Sentry's
documentation website](https://docs.sentry.io/clientdev/interfaces/http/).

#### Fingerprints

In cases where you do not send an exception, Sentry will try to group your
message by looking at differences in interfaces. In some cases, this is not
enough, and you will want to specify a particular grouping fingerprint, [as
explained in this part of the Sentry documentation](https://docs.sentry.io/learn/rollups/#custom-grouping).

To set a custom fingerprint for a particular event, this library provides the
`add-fingerprint!` function with the following arities:

- `(add-fingerprint! fingerprint)` Store the fingerprint in thread-local
  storage.
- `(add-fingerprint! context fingerprint)` Store the fingerprint in the
  user-specified map-like context (expected to be passed to `capture!`).

The contents of the :fingerprint entry is expected to be a list of strings.

#### Full example

The following examples send Sentry a payload with all extra interfaces provided
by this library, using the thread-local storage.

```clojure
(def dsn "https://098f6bcd4621d373cade4e832627b4f6:ad0234829205b9033196ba818f7a872b@sentry.example.com/42")
(add-breadcrumb! (make-breadcrumb! "The user did something" "com.example.Foo"))
(add-breadcrumb! (make-breadcrumb! "The user did something wrong" "com.example.Foo" "error"))
(add-user! (make-user "user-id" "test@example.com" "127.0.0.1" "username"))
(add-ring-request! ring-request)
(add-tag! :my_custom_tag "some value")
(capture! dsn (Exception.) {:another_tag "another value"})
```

Alternatively you can compose and send a valid payload using something like the following:

```clojure
(capture! dsn (-> {} (add-exception! e)
                     (add-user! (make-user "user-id")
                     (add-ring-request! ring-request)
                     (add-tag! :my_custom_tag "some value"))
```

### Testing

#### Unit tests

As usual in the clojure world, a simple `lein test` should run unit tests.

#### Integration tests

To ensure the results are correctly handled by Sentry and that this library
produces correct JSON payloads, a simple integration test can be run with

```bash
DSN=http://... lein test :integration
```

This will publish a test event in the project associated with the DSN with as
much test data as possible.

#### Testing programs using this library

In order to facilitate testing of programs using this library, a special
":memory:" DSN is supported. When passed to this library in place of a real
DSN, the payload map that would be sent to sentry in an HTTP request is instead
stored in the `http-requests-payload-stub` atom.

In your tests, you can assert that a Sentry payload conforming to your
expectations would have been sent to the sentry server with:

```clojure
(do
    (code-that-invokes-capture-once)
    (is (= 1 (count @http-requests-payload-stub))))
```

Users are responsible for cleaning the atom up between test runs, for example
using the `clear-http-stub` convenience function.

### Changelog

#### 0.4.4

- Fixes regression introduced in 0.4.3

#### 0.4.3

- Added support for more aleph options.

#### 0.4.2

- Fixed bug when threading breadcrumbs.
- Allowed users to inject an aleph connection pool via context.

#### 0.4.1
- Fixed tests
- Do not include the body of the http request in (add-ring-request!). Instead,
  provide a (add-full-ring-request!) helper as well.

#### 0.4.0

- Switch to using the aleph http client.
- Switch to using JSONista.
- Read hostname and linux distribution information from the filesystem instead
  of shelling out.

#### 0.3.3

- Fix behavior on empty lsb_release string

#### 0.3.1

- Fixed shelling out bug to gather hostname/lsb_release (failed in containers)

#### 0.3.0

- Made the client async by default, "fire and forget".
- Added an add-exception! helper to allow building payload by threading
- Allow passing event_id through payload or context
- Helper to create HTTP payloads from ring-compliant request maps

#### 0.2.0

- Added special ":memory:" DSN to allow easier testing of programs using this
  library.
- Added support for custom tags
- Added support for HTTP interface
- Added support for User interface
- Added support for Breadcrumbs interface
- Changed public API to support thread-local storage.
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
