<div align="center">
  <img src="./images/v4vbox.png" alt="3D box with 'V4V' on one side." width="128">
</div>

# boostbox

A simple API to store and serve boost metadata.

## Overview

Boostbox is a Clojure project designed to provide a straightforward API for storing boost metadata and serving it by ID in a standard format.

## Features

- Store boost metadata.
- Serve boost metadata by ID.
- Integrates with S3-compatible storage.

## Technologies

- [Nix](https://nixos.org/)
- [Clojure](https://clojure.org/)
- [devenv](https://devenv.sh/)
- [clj-nix](https://jlesquembre.github.io/clj-nix/)

### Clojure Libraries

- [aleph](https://github.com/aleph-io/aleph): Asynchronous HTTP server
- [babashka.http-client](https://github.com/babashka/http-client): HTTP client
- [cognitect.aws.client.api](https://github.com/cognitect-labs/aws-api): AWS SDK for S3 operations
- [dev.onionpancakes.chassis](https://github.com/onionpancakes/chassis): HTML generation (if needed for UI)
- [muuntaja](https://github.com/metosin/muuntaja): HTTP format negotiation, encoding, and decoding
- [reitit](https://github.com/metosin/reitit): HTTP routing

## Getting Started

### Prerequisites

- Nix package manager

### Development

1. Clone the repository.
1. Enter the development environment:

```sh
./dev.sh
```

This will set up the necessary dependencies and open the project with VSCode.

### Building

To build the project:

```sh
nix build
```

To build a container:

```sh
nix build .#container
```

## License

This project is licensed under the MIT License.
