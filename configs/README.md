# External configuration overrides

<a id="configs"></a>
<a id="назначение"></a>
<a id="структура"></a>
<a id="заметки"></a>

## Purpose and boundary

Optional operator-owned configuration data, without Java code. The packaged
[application.yml](../bootstrap/ioc-app/src/main/resources/application.yml) imports
`optional:file:./configs/application.yml` relative to the process working directory.
A clean checkout supplies no local override file; keep credentials and site data
out of version control.

## Structure and precedence

| Path | Responsibility |
|---|---|
| `application.yml` (optional, ignored) | Local overrides of packaged defaults |

Within normal application loading, precedence is packaged defaults < this import
< environment < JVM system properties < CLI. Lists are replaced as whole lists
by the highest-precedence declaration; partial indexed overrides do not merge
with lower-priority elements. Unknown `ioc.*`/`IOC_*` names fail preflight.

Installed systemd deployments load operator configuration from
`<prefix>/etc/application.yml`; follow the [configuration guide](../docs/guides/configuration.md)
and [deployment guide](../docs/guides/deployment.md), rather than treating this
checkout directory as the installation configuration location.
The [production template](../packaging/templates/application.yml) is a reviewed
starting point, not an automatically merged upgrade. Effective startup behavior
and controlled validation belong to [configuration internals](../docs/dev/configuration.md).
