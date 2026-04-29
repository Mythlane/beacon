# HytaleBeacon — Cahier des charges

> **Note sur le nom.** "Beacon" est un placeholder dans ce document. À renommer
> avant publication si tu trouves mieux. Quelques candidats courts : Beacon,
> Pulse, Lens, Scope, Lookout, Observe. Le namespace technique sera
> `com.mythlane.beacon` (à ajuster).

## Vue d'ensemble

### Vision

Beacon apporte de l'observabilité moderne (métriques, traces, logs, profils) à
Hytale via OpenTelemetry. Vendor-neutral : les utilisateurs choisissent leur
backend (Grafana Cloud, Datadog, Honeycomb, self-hosted LGTM, etc.) sans
modifier le plugin. Trois primitives :

1. **Auto-instrumentation JVM** via l'agent Java OTel (CPU, GC, mémoire, JDBC,
   HTTP) — gratuite, marche pour tout plugin tiers sans effort.
2. **Instrumentation domain Hytale** via plugin manuel (TPS, tick spans, player
   join SLI, event handlers, component access).
3. **Pack ops** : dashboards Grafana pré-construits, alertes pré-configurées,
   stack LGTM clé-en-main en Docker Compose.

### Positionnement

- **vs spark** (Minecraft) : continu, pas ponctuel. Distributed tracing inclus.
  Choix de backend libre.
- **vs HytaleMetrics** (SaaS) : self-hostable, pas de vendor lock-in, focus
  ops/SRE plutôt que produit/marketing.
- **vs UnifiedMetrics** (Minecraft) : pas un port. OTel-native, donc traces et
  logs en plus des métriques.

### Licence

MIT. Free to fork, modify, use commercially. Monétisation reportée à v1.0+.

### Stack technique

| Composant | Choix |
|---|---|
| Langage | Java 21 (LTS, toolchain JDK 25, bytecode target 21) |
| Build | Gradle 9.4 + Shadow |
| OTel SDK | opentelemetry-java 1.61+ (suit l'agent v2.27+) |
| Agent | opentelemetry-javaagent.jar bundlé en extension |
| Tests | JUnit 5 + Mockito + AssertJ |
| Stack démo | Grafana 10 + Tempo + Loki + Mimir + Pyroscope (LGTM+P) |

### Modèle de versioning

- v0.x = pre-1.0, breaking changes autorisés entre minor versions, documentés
  en CHANGELOG.
- v1.0 = API stable, semver strict.
- Chaque phase majeure = un bump minor (v0.1 → v0.2 → … → v1.0).
- Chaque sous-phase = un commit ou une PR mergée, jamais une version publiée
  intermédiaire.

### Architecture modulaire (à projeter dès v0.1)

```
hytale-beacon/
├── core/        # Pas de dépendance Hytale. SDK OTel + utilities.
├── instrum/     # Pas de dépendance Hytale. Instrumentation logique pure.
├── binding/     # Seul module qui importe com.hypixel.*
├── agent-ext/   # Extension agent OTel, packagée à part
└── dist/        # Shaded JAR final pour mods/
```

Même découpage que HytaleAsync, même raison : tester `core` et `instrum` sans
SDK Hytale, garder le blast radius d'un breaking change SDK confiné dans
`binding`.

---

## Phase 1 — v0.1 "Foundation" (CurseForge launch)

### Objectifs

Le minimum viable utile. Un server admin installe le JAR, configure deux
variables d'environnement (endpoint OTLP + clé), et obtient en 5 minutes :
- Un dashboard Grafana avec TPS, MSPT, mémoire, GC, players online.
- Toutes les métriques JVM standard (gratis via auto-instrumentation).
- Un docker-compose qui démarre la stack LGTM en local pour démonstration.

**Pas de tracing manuel à ce stade.** Pas d'event handlers, pas de spans
custom. Juste les fondations propres + métriques système. Le "wow distribué"
c'est la phase 5.

### Critères de release v0.1

- [ ] Le JAR drop dans `mods/` et boote sans erreur sur un serveur Hytale dev.
- [ ] Avec un endpoint OTLP configuré, les métriques JVM apparaissent dans Grafana.
- [ ] Le dashboard "Server Health" se charge depuis le JSON livré, sans édit manuel.
- [ ] Le docker-compose démarre en `docker compose up -d` et expose Grafana sur :3000.
- [ ] Overhead CPU < 1% sur un serveur 20 joueurs (à mesurer, voir 1.10).
- [ ] Tests `core` + `instrum` verts. Build CI vert sur GitHub Actions.
- [ ] README + quickstart de 5 minutes.

### Sous-phases

#### 1.1 — Scaffold projet

**Livrable.** Repo initialisé, Gradle multi-module configuré (toolchain JDK 25,
bytecode target Java 21 LTS, source set Java pur). LICENSE MIT. Workflow
`build.yml` GitHub Actions.

**Critères d'acceptation.** `./gradlew build` vert sur fresh clone. Workflow CI
vert sur push. Modules `core`, `instrum`, `binding`, `agent-ext`, `dist` créés
avec leur `build.gradle` (Groovy DSL) et un fichier source placeholder.

**Tests.** Aucun test fonctionnel — c'est de l'infra. Vérification manuelle
build vert + CI vert.

#### 1.2 — Dépendances OTel et agent embarqué

**Livrable.** `gradle/libs.versions.toml` enrichi avec opentelemetry-java
(api, sdk, exporters OTLP gRPC + HTTP). Module `agent-ext` qui télécharge
l'agent officiel OTel et le repackage en extension (cf. la doc agent
extensions OTel). `dist/build.gradle` qui produit deux JARs : le shaded
plugin + l'agent extension.

**Critères d'acceptation.**
- `./gradlew :dist:shadowJar` produit `dist/build/libs/beacon-0.1.0-SNAPSHOT.jar`.
- `./gradlew :agent-ext:agentJar` produit `dist/build/libs/beacon-agent-0.1.0-SNAPSHOT.jar`.
- Le shaded plugin n'embarque PAS l'agent (séparé).
- Aucune duplication de classes, mergeServiceFiles activé pour le shaded JAR.

**Tests.** Build vert. Inspection manuelle du contenu des JARs (`unzip -l`)
pour confirmer absence de doublons.

#### 1.3 — Configuration loader

**Livrable.** Classe `core/config/BeaconConfig.java`. Charge la config dans cet
ordre de précédence (le plus haut gagne) :
1. Variables d'environnement (`OTEL_*` standard + `BEACON_*` pour les options spécifiques).
2. Fichier `mods/Mythlane.Beacon/config.toml` (création automatique avec defaults au premier boot).
3. Defaults compilés.

Champs minimum v0.1 :
- `otel.exporter.endpoint` (URL OTLP)
- `otel.exporter.protocol` (`grpc` | `http/protobuf`)
- `otel.exporter.headers` (auth, e.g. `Authorization=Basic …`)
- `service.name` (default : `hytale-server`)
- `service.namespace` (default : vide)
- `deployment.environment` (default : `production`)
- `beacon.enabled` (default : `true`)
- `beacon.metrics.interval` (default : 30s)

**Critères d'acceptation.**
- Les trois sources de config se résolvent dans le bon ordre.
- Defaults raisonnables : si rien n'est configuré, le plugin loggue une fois
  "no OTLP endpoint configured, telemetry disabled" et n'envoie rien.
- Le config.toml généré au premier boot est commenté ligne par ligne.

**Tests.**
- `BeaconConfigTest` : couvre les 6 cas de précédence (env > file, file > default, etc.).
- Test du fallback "pas d'endpoint" : aucune exception levée, telemetry off.
- Test du parsing TOML invalide : message d'erreur clair, boot continue avec defaults.

#### 1.4 — Plugin lifecycle wiring

**Livrable.** `binding/BeaconPlugin.java` qui étend `JavaPlugin`. Au `start()` :
1. Charge la config.
2. Initialise le `OpenTelemetrySdk` global.
3. Démarre les metric readers.
4. Logue "Beacon active, exporting to {endpoint}" ou la version OFF.

Au `shutdown()` : flush propre des batches en cours, close du SDK avec timeout
de 5s.

**Critères d'acceptation.**
- Boot du serveur ≤ +200ms vs serveur sans plugin (à mesurer).
- Shutdown propre, pas de thread leak (vérifié via `ThreadMXBean.getThreadCount()` avant/après).
- Si l'endpoint OTLP est down, le plugin ne crash pas — il logue les retries
  exponentiels et le serveur continue normalement.

**Tests.**
- `BeaconLifecycleTest` (manuel, sur serveur dev) : boot, vérif des logs,
  shutdown, vérif absence de threads zombies.
- Test endpoint down : démarrer avec un endpoint inaccessible, vérifier que
  le serveur reste responsive et les retries passent en backoff.

#### 1.5 — Métriques JVM via auto-instrumentation

**Livrable.** L'agent extension active les instrumentations runtime-telemetry
(JVM memory, GC, threads, classloader, CPU). Les métriques sortent en OTLP
vers l'endpoint configuré.

**Critères d'acceptation.** Un serveur dev avec endpoint configuré sur la
stack LGTM locale doit montrer dans Grafana, sans aucune config supplémentaire :
- `process.runtime.jvm.memory.usage`
- `process.runtime.jvm.gc.duration`
- `process.runtime.jvm.threads.count`
- `process.runtime.jvm.classes.loaded`
- `process.cpu.utilization`

**Tests.**
- Démarrer le serveur, attendre 2 min, query Tempo/Mimir : les 5 métriques
  doivent avoir au moins 4 data points.
- Forcer un GC majeur via `System.gc()` depuis un test plugin et vérifier
  l'apparition d'un event GC dans la timeseries.

#### 1.6 — Métrique TPS / MSPT

**Livrable.** Première instrumentation Hytale-spécifique. Une métrique
`hytale.tps` (gauge) et `hytale.mspt` (histogram) par world, exportée toutes
les 30s par défaut.

Implémentation : un `Runnable` posté toutes les secondes sur le world
executor, mesure le temps écoulé depuis le tick précédent. Moyenne mobile
sur 5s, 1min, 5min, 15min comme spark.

Attributes : `hytale.world.uuid`, `hytale.world.name`.

**Critères d'acceptation.**
- TPS au repos sur un world vide ≈ 20.0 (target Hytale).
- Surcharge artificielle (boucle de 100ms posée sur le world thread) → MSPT
  remonte à >100ms, TPS chute en dessous de 20.
- Multi-world : 2 worlds simultanés exportent 2 séries distinctes.

**Tests.**
- `TpsCalculatorTest` (unit) : timestamps mockés, vérification des moyennes
  mobiles.
- Test d'intégration sur dev server : induire la lag, vérifier dans Grafana.

#### 1.7 — Métrique players online

**Livrable.** Gauge `hytale.players.online` avec attributes `world.uuid` et
`world.name`. Mise à jour sur `PlayerReadyEvent` et `PlayerDisconnectEvent`.

**Critères d'acceptation.**
- Joueur rejoint un world → la gauge pour ce world incrémente de 1.
- Joueur se disconnect → décrément de 1.
- Joueur change de world (transfer) → -1 sur l'ancien, +1 sur le nouveau.

**Tests.**
- Test de cohérence : N players → somme des gauges per-world = N.
- Test du transfer : joueur change de world, intégrale temporelle stable.

#### 1.8 — Stack LGTM Docker Compose

**Livrable.** Dossier `examples/lgtm-stack/` avec :
- `docker-compose.yml` qui démarre Grafana 10, Tempo, Loki, Mimir, Pyroscope.
- `grafana/provisioning/datasources/` configurés pour Tempo+Loki+Mimir.
- `otel-collector-config.yaml` qui reçoit l'OTLP et route vers les backends.
- `README.md` : `docker compose up -d`, ouvrir `localhost:3000` (admin/admin),
  config Beacon plugin pointe sur `http://localhost:4317`.

**Critères d'acceptation.**
- `docker compose up -d` démarre en moins de 60s sur une machine modeste.
- Grafana est accessible avec login admin/admin.
- Les 3 datasources sont présentes et "test" passe en vert.
- Une métrique envoyée par Beacon apparaît dans Grafana en moins de 60s.

**Tests.**
- Test sur une VM fresh : suivre uniquement le README, ouvrir Grafana, voir
  les métriques. Si ça demande plus d'1 commande hors du compose, le README
  est cassé.

#### 1.9 — Dashboard Grafana "Server Health"

**Livrable.** Un seul dashboard JSON exporté, importable via Grafana UI ou
provisionné via le compose. Panels :
- TPS (timeseries) avec seuil rouge à 18.
- MSPT p50/p95/p99 (timeseries).
- JVM Memory (heap + non-heap).
- GC pause time + frequency.
- Threads count.
- Players online (par world).
- CPU utilization.

**Critères d'acceptation.**
- Le dashboard se charge sans erreur sur Grafana 10.
- Toutes les variables (ex. `$world`) sont peuplées automatiquement à partir
  des métriques disponibles.
- Pas de "no data" sur un serveur dev qui run depuis 2 min.

**Tests.**
- Test visuel : screenshot du dashboard, vérif que tous les panels affichent
  des données réelles.

#### 1.10 — Mesure d'overhead

**Livrable.** Procédure de bench documentée dans `docs/perf.md`. Compare un
serveur "vanilla" (sans Beacon) à un serveur Beacon-instrumenté, avec 20
joueurs simulés (bots), pendant 10 minutes. Mesure CPU moyen, mémoire, TPS.

**Critères d'acceptation.**
- Overhead CPU < 1% en moyenne (target).
- Overhead mémoire < 50 MB.
- TPS Beacon ≥ TPS vanilla - 0.05 (différence marginale).
- Si l'un de ces seuils est franchi, le release est bloqué jusqu'à
  optimisation.

**Tests.**
- Bench répété 3 fois, médiane retenue.
- Documentation des résultats avec commit, hardware, paramètres serveur.

#### 1.11 — Documentation v0.1

**Livrable.**
- `README.md` racine avec quickstart 5 min.
- `docs/getting-started.md` : install, config minimum, premier dashboard.
- `docs/configuration.md` : exhaustif sur la config (champs, exemples).
- `docs/backends.md` : guides "Comment configurer Beacon avec Grafana Cloud /
  Datadog / Honeycomb / self-hosted LGTM". Une section par backend.
- `docs/perf.md` : résultats du bench 1.10.

**Critères d'acceptation.**
- Quickstart 5 min testé par une personne extérieure (toi avec un timer).
- Aucun lien cassé.
- Les 4 backends documentés ont été testés au moins une fois (Grafana Cloud
  free tier facile, Datadog free trial, Honeycomb free, self-hosted via le
  compose).

#### 1.12 — Soumission CurseForge

**Livrable.** Page CurseForge créée avec :
- Description, screenshots du dashboard, instructions install.
- Tags : utility, library, monitoring, performance, optimization.
- Lien GitHub.
- Lien doc.

**Critères d'acceptation.** Soumission acceptée par modération CurseForge, JAR
téléchargeable, version 0.1.0 visible.

**Tests.** Téléchargement du JAR depuis CurseForge sur une machine fresh,
test du flow install complet.

---

## Phase 2 — v0.2 "Domain instrumentation"

### Objectifs

Le différenciateur principal. v0.1 prouve la plomberie. v0.2 ajoute les
signaux que personne d'autre ne donne sur Hytale : tick spans, traces
end-to-end de player join, attribution per-plugin de la consommation tick.

### Critères de release v0.2

- [ ] Trace de player join visible dans Tempo, du connect au PlayerReadyEvent.
- [ ] Tick spans pour chaque world, avec slow tick alerting (>50ms).
- [ ] Event handler tracing actif pour les events natifs Hytale.
- [ ] Dashboard "Player Experience SLI" et "Plugin Performance" livrés.
- [ ] Overhead total (v0.1 + v0.2) < 2% CPU.

### Sous-phases

#### 2.1 — Tick span per world

**Livrable.** Pour chaque tick de chaque world, un span avec attributes
`world.uuid`, `world.name`, `tick.duration_ms`, `entity.count`,
`player.count`. Le span n'est pas exporté pour TOUS les ticks (volume
ingérable) — sampling head-based : 1 sur 100 par défaut, configurable.

Exception : si un tick dépasse un seuil (default 50ms), il est marqué
`slow_tick=true` et exporté **toujours**, indépendamment du sampling.

**Critères d'acceptation.**
- Sampling 1/100 vérifiable dans Tempo (volume cohérent).
- Tous les ticks > 50ms apparaissent dans Tempo avec `slow_tick=true`.
- Le sampling rate est reconfigurable via config.toml sans restart serveur.

**Tests.**
- Test d'unité : `TickSamplerTest` valide la décision sample/nosample.
- Test d'intégration : forcer 10 slow ticks, vérifier que les 10 sont dans
  Tempo.

#### 2.2 — Player join SLI trace

**Livrable.** Trace complète "player_join" :
- Span racine ouvert au `PlayerConnectEvent` (ou équivalent SDK).
- Spans enfants pour chaque étape : auth, profile load, world spawn,
  component init, ready event.
- Attributes : `player.uuid` (hashable selon config), `player.name`,
  `world.target`, `auth.method`.

**Critères d'acceptation.**
- Trace complète visible dans Tempo, durée totale = temps réel
  connection→ready (à 50ms près).
- Les étapes sont en relation parent/enfant correcte.
- Si une étape échoue (ex. auth fail), le span est marqué erreur.

**Tests.**
- Test d'intégration : 10 joueurs simulés, 10 traces complètes dans Tempo.
- Test du cas erreur : refuser un joueur via PlayerConnectEvent cancellation,
  vérifier la trace `error=true` correctement attribuée.

#### 2.3 — Event handler tracing

**Livrable.** Wrapping automatique du registry d'events. Chaque appel à un
handler enregistré devient un span enfant du span courant (s'il y en a un).
Attributes : `event.type`, `plugin.name` (qui a enregistré le handler),
`handler.duration_ms`.

Implémentation via une extension du `EventRegistry` qui intercepte les
`register*` calls et wrap le handler.

**Critères d'acceptation.**
- Un handler de `PlayerChatEvent` enregistré par un plugin tiers apparaît
  dans Tempo avec `plugin.name=<le bon plugin>`.
- Un handler lent (>10ms) est trackable dans le dashboard "Plugin Performance".

**Tests.**
- Test d'intégration avec un plugin de test qui enregistre 3 handlers de
  poids différents, vérification dans Tempo.

#### 2.4 — Component access metrics

**Livrable.** Compteur `hytale.component.access_count` avec attributes
`component.type`, `access.kind` (read|write). Sampling à 1/1000 par default
pour éviter cardinality explosion.

**Critères d'acceptation.**
- Les composants les plus accédés visibles dans un panel "top components".
- Pas plus de 50 séries de cardinalité au repos.

**Tests.**
- Test du sampling rate : valider qu'à 1/1000, l'overhead est < 0.1% sur un
  workload synthétique.

#### 2.5 — Attribution per-plugin

**Livrable.** Métriques `plugin.tick.contribution_ms` (somme de tick time
attribuable à un plugin via ses event handlers + tâches scheduled).
Permet la vue "qui consomme combien" sans profiler.

**Critères d'acceptation.**
- Un plugin avec un handler lourd (e.g. `Thread.sleep(10ms)` dans son
  PlayerMoveEvent) apparaît en haut du dashboard "Plugin Performance".

**Tests.**
- Test avec 2 plugins de poids différents (un lourd, un léger), vérifier
  que la métrique reflète l'écart.

#### 2.6 — Dashboards "Player Experience" et "Plugin Performance"

**Livrable.** Deux nouveaux dashboards JSON, importables.

**Player Experience SLI** :
- Histogram durée player join (p50/p95/p99/p999).
- SLI : % of joins under 3s sur les 5 dernières minutes.
- Top 10 slowest joins (table avec lien vers la trace Tempo).
- Error rate joins.

**Plugin Performance** :
- Top 10 plugins par consommation tick (bar chart).
- Heatmap slow handlers par plugin.
- Tableau "events per second" par plugin.

**Critères d'acceptation.** Charge sans erreur, datasources auto, panels
peuplés sur dev server après 5 min de jeu.

#### 2.7 — Documentation v0.2

**Livrable.**
- `docs/instrumentation.md` : explique tick spans, player join SLI, event
  handler tracing.
- `docs/sli-slo.md` : guide pour définir des SLOs sur ses propres métriques.
- README mis à jour avec les nouveaux screenshots.

---

## Phase 3 — v0.3 "Configuration & sécurité"

### Objectifs

Rendre le plugin déployable en production sérieuse. Sampling fin, contrôle
de cardinalité, GDPR, hot-reload. Pas de nouvelles métriques — uniquement
maturité.

### Critères de release v0.3

- [ ] Toutes les options critiques sont configurables sans restart.
- [ ] UUID hashing fonctionnel et opt-in/out testé.
- [ ] Cardinality limits empêchent les explosions involontaires.
- [ ] Plugin déployable en environnement RGPD-strict.

### Sous-phases

#### 3.1 — Sampling configurable par signal

**Livrable.** Sampling rules dans `config.toml` :
- Per signal type (traces vs metrics vs logs).
- Per event name (e.g. `tick.duration > 50ms : 1.0, else : 0.01`).
- Per plugin name (whitelist/blacklist).

**Critères.** Une règle "sample 100% des player_join, 1% des ticks normaux"
fonctionne sur un dev server.

**Tests.** `SamplerTest` couvrant 8+ scénarios.

#### 3.2 — Cardinality controls

**Livrable.** Limites configurables : max séries par metric, allowlist
d'attributes (les autres sont droppés). Si limite atteinte, drop des nouvelles
séries + log warning + métrique `beacon.dropped_series_count`.

**Critères.** Forcer 10k séries d'un coup (test synthétique), vérifier que
le drop fonctionne sans crash.

**Tests.** Stress test cardinalité.

#### 3.3 — Privacy / GDPR

**Livrable.**
- UUID hashing optionnel (SHA-256, salt configurable).
- Attribute redaction list (champ wildcard pattern).
- Opt-out par joueur via API publique : `Beacon.optOut(uuid)` exclut tout
  signal lié à ce joueur.

**Critères.**
- Avec hashing on, aucun UUID brut en clair dans Tempo/Mimir.
- Un joueur opt-out n'apparaît dans aucune trace ni metric.

**Tests.**
- Test du hashing : vérifier déterminisme (même UUID → même hash).
- Test du opt-out : faire jouer 2 joueurs, opt-out un des deux, vérifier
  l'absence dans les exports.

#### 3.4 — Resource attributes configurables

**Livrable.** Section `resource_attributes` dans config.toml. Permet d'ajouter
arbitrairement des attributes (ex. `region=eu-west`, `tier=production`,
`network=mythlane`).

**Critères.** Les attributes apparaissent sur tous les signaux émis, sans
exception.

#### 3.5 — Hot-reload

**Livrable.** Une commande `/beacon reload` qui recharge la config sans
restart. Limitation acceptée : la `service.name` n'est pas reloadable
(contrainte SDK OTel).

**Critères.** Modifier le sampling rate dans config.toml, lancer `/beacon
reload`, vérifier l'effet immédiat dans Tempo.

#### 3.6 — Per-signal toggles

**Livrable.** Activer/désactiver granulaire : metrics, traces, logs, profiles
indépendamment.

**Critères.** Avec `traces.enabled=false`, aucun span ne sort, mais les
metrics continuent.

#### 3.7 — Documentation v0.3

`docs/sampling.md`, `docs/privacy.md`, `docs/cardinality.md`. Trois guides
distincts, exemples concrets.

---

## Phase 4 — v0.4 "Plugin SDK"

### Objectifs

Permettre aux autres plugin authors d'instrumenter leur propre logique avec
Beacon. Devient une plateforme, pas juste un outil ops.

### Critères de release v0.4

- [ ] API publique stable (`Beacon.tracer()`, `Beacon.meter()`, helpers Java).
- [ ] Documentation pour plugin authors.
- [ ] Au moins 1 plugin externe (réécrire un example HytaleAsync) utilisant
      l'API.

### Sous-phases

#### 4.1 — API publique d'instrumentation

**Livrable.** Classe `core/api/Beacon.java` qui expose :
```java
Tracer Beacon.tracer(String instrumentationName);
Meter  Beacon.meter(String instrumentationName);
<R> R  Beacon.span(String name, Function<SpanScope, R> block);
LongCounter Beacon.counter(String name, Attributes attrs);
```

**Critères.** L'API permet de réécrire un example BountyBoard avec ses propres
spans en moins de 20 lignes.

#### 4.2 — Annotations Java (`@Traced`)

**Livrable.** `@Traced` sur méthodes Java → wrap automatique en span. Via
Java Annotation Processing standard (`javax.annotation.processing.Processor`,
enregistré via `META-INF/services/`), zéro reflection runtime.

**Critères.** Une méthode annotée `@Traced` dans un plugin tiers produit des
spans dans Tempo.

#### 4.3 — Propagation de contexte asynchrone

**Livrable.** Helper `Beacon.taskWrapping(Executor)` (basé sur
`Context.taskWrapping(executor)` d'OTel) qui propage automatiquement le
contexte OTel à travers un Executor. Support natif des virtual threads
Java 21 : un span ouvert avant `Thread.ofVirtual().start(...)` reste accessible
dans le bloc — le `Context` est porté par la `ContextStorage` thread-local
standard d'OTel.

**Critères.** Un plugin author wrap son `Executor` via
`Beacon.taskWrapping(executor)` ou démarre une virtual thread via
`Thread.ofVirtual().start(...)`, et le contexte OTel propage dans le travail
asynchrone (span enfant correctement parenté dans Tempo).

#### 4.4 — Documentation pour plugin authors

`docs/plugin-developers.md` : guide complet, semantic conventions à respecter,
exemples.

#### 4.5 — Example "instrumented BountyBoard"

Réécrire l'example BountyBoard de HytaleAsync en exportant ses propres spans
(bounty placement, payout, broadcast). Devient l'example showcase pour les
plugin authors.

---

## Phase 5 — v0.5 "Cross-server tracing"

### Objectifs

La feature "wow" pour réseaux multi-serveurs. Trace context préservé à travers
les transfer packets Hytale. Permet de debugger "le joueur a eu 8s de
loading total en traversant 3 worlds".

### Critères de release v0.5

- [ ] W3C traceparent encodé/décodé via les transfer packets.
- [ ] Trace cross-server visible dans Tempo, spans des 2 serveurs corrélés.
- [ ] Dashboard "Cross-Server Network" livré.

### Sous-phases

#### 5.1 — Encodage W3C traceparent dans transfer packet

**Livrable.** À l'émission d'un transfer packet, sérialiser le traceparent
courant dans le payload (4KB disponibles). À la réception, désérialiser et
restaurer le contexte.

**Critères.** Un span ouvert sur le serveur A, suivi d'un transfer vers B,
suivi d'un span sur B, doit former une trace unique dans Tempo.

#### 5.2 — Player journey trace

**Livrable.** Trace longue durée "player_session" qui couvre la totalité de
la session du joueur, avec sub-spans pour chaque world traversé et chaque
event majeur.

**Critères.** Un joueur qui se connecte, traverse 3 worlds, et se déconnecte
produit UNE trace avec la timeline complète.

#### 5.3 — Dashboard "Cross-Server Network"

**Livrable.** Vue topologique des serveurs, avec flux de joueurs, latence
inter-serveurs, top routes.

#### 5.4 — Setup multi-serveurs documenté

**Livrable.** `docs/multi-server.md` : exemple concret avec 2 serveurs Hytale
qui partagent un OTel Collector central.

---

## Phase 6 — v1.0 "Production-ready"

### Objectifs

Polish final, garanties d'API stable, profiling continu, pack ops complet.

### Critères de release v1.0

- [ ] API stable, semver strict à partir de v1.0.0.
- [ ] 15+ dashboards Grafana livrés.
- [ ] 10+ alert rules pré-configurées avec SLO templates.
- [ ] Continuous profiling intégré (Pyroscope).
- [ ] Stress test 1000 joueurs simulés passe.
- [ ] Documentation exhaustive.

### Sous-phases

#### 6.1 — Continuous profiling intégration

**Livrable.** Intégration Pyroscope/Phlare. CPU profile continu visible dans
Grafana à côté des traces.

#### 6.2 — SLO templates

**Livrable.** Pack de SLO Grafana provisionnable :
- "99% des player joins sous 3s sur 30 jours"
- "TPS médian ≥ 19.5 sur 7 jours"
- "Error rate < 0.1% sur les event handlers"

Avec burn rate alerts (multi-window multi-burn).

#### 6.3 — Alert rules pack

**Livrable.** 10+ règles Prometheus/Grafana :
- TPS < 18 pendant 5min
- GC pause > 500ms
- Memory > 90%
- Player join p99 > 5s
- Plugin handler p99 > 100ms
- Drop rate > 1%
- etc.

#### 6.4 — Dashboard pack complet

**Livrable.** 15+ dashboards :
- Server Health (v0.1, raffiné)
- Player Experience SLI (v0.2, raffiné)
- Plugin Performance (v0.2, raffiné)
- Cross-Server Network (v0.5, raffiné)
- Memory & GC Detail
- Event Handler Profiler
- Component Access Heatmap
- World by World
- Top Slow Operations
- Error Tracking
- Network Latency
- Database Performance
- Custom Metrics Explorer
- Profiling Flame Graphs
- Executive Summary

#### 6.5 — Stress test 1000 joueurs

**Livrable.** Procédure documentée pour stress test avec 1000 bots simulés.
Acceptation : Beacon < 5% overhead à 1000 joueurs.

#### 6.6 — API stability commitment

**Livrable.** Document `STABILITY.md` :
- Liste des packages API stable (pas de breaking changes mineurs).
- Liste des packages internal/experimental.
- Procédure de deprecation : 2 versions de warning avant suppression.

#### 6.7 — Documentation v1.0

Refonte complète :
- `docs/` réorganisé par persona (server admin, plugin author, contributor).
- Tutoriels vidéo (optionnel).
- Cookbook : 10 recipes courts pour les cas réels.

---

## Annexes

### A. Conventions de nommage des métriques

Suit OpenTelemetry semantic conventions strictement où applicable.
Custom metrics préfixées `hytale.*`. Exemples :
- `hytale.tps`
- `hytale.mspt`
- `hytale.players.online`
- `hytale.world.entity.count`
- `hytale.component.access.count`
- `hytale.plugin.tick.contribution_ms`

### B. Stratégie de tests globale

- Unit tests : Java pur dans `core` et `instrum`. Cible 80%+ coverage.
- Integration tests : sur dev server Hytale, scriptés via la JVM de test.
- Bench / perf : à chaque phase, comparaison vanilla vs instrumented.
- E2E backend tests : compose LGTM démarré, plugin push, queries Mimir/Tempo
  pour vérifier l'arrivée des signaux.
- Stress tests : à v0.4 et v1.0, simulation N joueurs.

Aucune sous-phase n'est mergée sans tests. Pas de test = pas de release.

### C. Risques identifiés

- **Performance overhead.** Mitigation : sampling agressif, cardinality
  limits, bench obligatoire à chaque phase.
- **Cardinality explosion.** Mitigation : phase 3.2 dédiée, allowlists par
  défaut.
- **OTel API breaking changes.** L'agent OTel est en v2.x stable, mais les
  semantic conventions évoluent. Mitigation : lock sur major version, suivi
  trimestriel.
- **Dépendance SDK Hytale.** Comme HytaleAsync : module `binding` isolé.
- **Maintenance multi-backend.** Mitigation : tester contre 2 backends max
  (Grafana Cloud + self-hosted LGTM) en CI, le reste documenté best-effort.
- **Complexité onboarding.** Mitigation : compose LGTM clé-en-main, quickstart
  5 min validé chronométré.

### D. Estimations de temps part-time (1 dev, 10-15h/semaine)

| Phase | Estimation |
|---|---|
| v0.1 Foundation | 4-6 semaines |
| v0.2 Domain instrumentation | 3-4 semaines |
| v0.3 Configuration & sécurité | 2-3 semaines |
| v0.4 Plugin SDK | 3-4 semaines |
| v0.5 Cross-server | 2-3 semaines |
| v1.0 Production-ready | 4-6 semaines |
| **Total** | **18-26 semaines** |

À étaler sur 6-9 mois calendaires en part-time, avec marge pour les
imprévus, la documentation, et les retours utilisateurs entre versions.

### E. Critères de "fait" (Definition of Done) — applicable à chaque sous-phase

Une sous-phase n'est marquée fait que si :
1. Code écrit + revu (par toi, ou par un sub-agent Claude Code en review).
2. Tests unitaires verts.
3. Tests d'intégration verts (s'applicable).
4. CI verte sur le PR.
5. Bench perf si la sous-phase touche un hot path.
6. Documentation à jour pour cette sous-phase.
7. Conventional Commit pushé sur main.

### F. Décisions à prendre avant de démarrer

- [ ] Nom final du projet (Beacon ou autre).
- [ ] Namespace Java (`com.mythlane.beacon` ou autre).
- [ ] Repo GitHub : public dès v0.1 ou privé jusqu'à release.
- [ ] CurseForge ou GitHub Releases comme distribution principale ? (CurseForge
      pour discoverabilité, GitHub Releases pour devs.)
- [ ] Marketing : compte Twitter/X dédié ou via @Mythlane existant.
- [ ] Discord : channel dédié sur ton serveur Mythlane existant ou nouveau.
