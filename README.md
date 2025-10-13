# Secure Gateway Tunneler

[![codecov](https://codecov.io/github/AdwaitChavan/secure-pomerium-tunneler/graph/badge.svg?token=OBCNTQFDOH)](https://codecov.io/github/AdwaitChavan/secure-pomerium-tunneler)

<!-- Plugin description -->
A Jetbrains Gateway plugin which handles authentication and tunneling through to a
[Pomerium](https://pomerium.io) route for connecting to a IntelliJ backend.
<!-- Plugin description end -->

## Usage

The plugin adds a `GatewayConnectionProvider` to handle `jetbrains-gateway://` 
links. This provider requires the following parameters in the links query parameters:

1) `pomeriumRoute`: A URL encoded url for the Pomerium route
2) `connectionKey`: A URL encoded join link provided by the backend.
for example, by using `remote-dev-server.sh status`. It will be the one that looks like
`tcp://127.0.0.1:5990#jt=...`
3) `pomeriumInstance`: (Optional) a hostname of the pomerium instance to use. By default,
the `pomeriumRoute` will be used to connect, but if DNS does not point to pomerium, this can be used

In addition, this plugin implements `GatewayConnector` to allow connecting using the 
instance via the `jetbrains-gateway://` link without going through the browser (useful for testing)

## Code Coverage

This project uses [Codecov](https://about.codecov.io/) for test coverage metrics, integrated via GitHub Actions. 
Coverage reports are generated on every build and uploaded to Codecov for visualization and tracking.

For protected branches (e.g., main), a Codecov upload token is required and already configured as a 
repository secret (`CODECOV_TOKEN`). If you fork this repository or set up a similar workflow, add your 
own token as a secret and ensure coverage reports are generated at `build/reports/kover/report.xml`. 
For more details, see the [Codecov docs](https://docs.codecov.com/docs/codecov-tokens).


