# PLAN técnico — Implementación Java (Secuencial + Paralela) y Métricas

**Documento:** plan.txt
**Basado en:** spec
**Lenguaje:** Java (recomendado Java 17 o superior)
**Estrategia paralela elegida (mínimo):** Paralelización por agentes (threads)
**Medición de tiempos:** timestamps impresos por consola + duración calculada

## 1. Arquitectura técnica (alto nivel)

### Componentes principales

1. **Cargador y validador de rejilla**

   * Lee archivo de texto.
   * Construye estructura interna `Grid`.
   * Valida consistencia (caracteres, rectángulo, cruces, orientación de calles).

2. **Modelo del dominio**

   * `Grid`, `CellType`, `Direction`, `Vehicle`, `TrafficLight`.
   * Ocupación por celda y dirección (“carriles” virtuales).

3. **Motor de simulación**

   * `SimulationEngine` interfaz: `run(config)`.
   * `SequentialEngine`: referencia para correctitud.
   * `ParallelEngine`: actualiza agentes en paralelo.

4. **Métricas y benchmarking**

   * `MetricsCollector`: flujo/detenidos por tick, promedios.
   * `BenchmarkRunner`: corre secuencial/paralelo, mide tiempos, calcula speedup/eficiencia.

5. **I/O de resultados**

   * Logs con timestamps.
   * Exportación de CSV (recomendado) para gráficas.

## 2. Diseño de datos y estructuras

### 2.1 Tipos base

* `enum CellType { ROAD, INTERSECTION, BLOCK }`
* `enum Direction { NORTH, SOUTH, EAST, WEST }`

  * Cada dirección tiene `(dx, dy)` y un `index` en `[0..3]`.
  * Utilidad: `isHorizontal(dir)` y `isVertical(dir)`.

### 2.2 Representación de la rejilla

* `Grid`:

  * `int width, height`
  * `CellType[] cells` (size = width*height)
  * `boolean[] isTransitable` (derivable)
  * `Intersection[] intersections` (lista de posiciones donde `cells[idx] == INTERSECTION`)

**Indexado:**

* `idx = y*width + x`
* Validación de rango al acceder.

### 2.3 Ocupación por celda y dirección (capacidad 2)

Para cumplir “2 vehículos, uno en cada dirección opuesta”:

* `int[] occ` de tamaño `numCells * 4`.
* `occ[idx*4 + dirIndex] = vehicleId` o `-1` si vacío.

**Restricción de consistencia por celda:**

* En cualquier celda, solo puede haber ocupación en:

  * E y W (máximo 2, uno cada uno), **o**
  * N y S (máximo 2, uno cada uno),
  * pero **no mezcla** horizontal con vertical.

Esto se garantiza por reglas de entrada y validación.

### 2.4 Vehículos (representación eficiente)

Para rendimiento y paralelismo, usar arrays paralelos (evitar objetos por tick):

* `int vehicleCount`
* `int[] vehicleCellIdx` (posición)
* `int[] vehicleDirIdx` (0..3)
* `boolean[] vehicleStopped` (estado en el tick actual o anterior)
* `int[] vehicleId` implícito por índice.

### 2.5 Semáforos

* Para cada intersección:

  * Estado: `H_GREEN` o `V_GREEN`
  * Contador/temporizador para modo periódico.

Representación:

* `TrafficLight[] lights` alineado con `intersections[]` (misma longitud)
* Mapeo `intersectionIdxByCell[idx] = i` o `-1` si no es intersección (para lookup O(1)).

## 3. Algoritmo de simulación (definición operativa)

### 3.1 Fases por tick (sincrónicas)

En cada tick:

**Fase A — actualizar semáforos**

* Periódico: si `tick % period == 0` entonces alterna.
* Adaptativo (opcional): decide en base a congestión local (ver sección 6).

**Fase B — calcular propuestas de movimiento (lectura del estado actual)**
Para cada vehículo i:

* Lee `(cellIdx, dir)` desde arrays.
* Si está en `INTERSECTION`:

  * Decide si gira con probabilidad `pTurn`.
  * Determina dirección de salida (straight o turn).
  * Calcula `targetCellIdx`.
  * Verifica que `targetCell` sea transitable y que el carril (slot dirección) esté libre en `occ`.
  * Si libre → propone mover.
  * Si no → propone quedarse (detenido).
* Si está en `ROAD`:

  * Calcula `nextCellIdx` por `dir`.
  * Si `nextCell` es `INTERSECTION`:

    * Verifica semáforo: permite el eje de `dir`.
    * Verifica que la intersección no tenga ocupación perpendicular (según `occ`).
    * Verifica slot dirección libre.
  * Si `nextCell` es `ROAD`:

    * Verifica slot dirección libre.
  * Si ok → propone mover; si no → quedarse.

**Fase C — resolución de conflictos**

* Hay conflicto si múltiples vehículos proponen el mismo `(targetCellIdx, targetDir)`.
* Regla determinista: **gana el menor vehicleId** (o menor índice).
* Resultado: para cada propuesta hay un `accepted=true/false`.

**Fase D — aplicar movimientos (escritura a nuevo estado)**

* Se usa doble buffer: `occNext` (limpio).
* Para cada vehículo:

  * Si su propuesta fue aceptada: actualiza `vehicleCellIdx`, `vehicleDirIdx`, escribe en `occNext[targetKey]`.
  * Si no: escribe su estado actual en `occNext[currentKey]`.
  * Actualiza `vehicleStopped[i]` y métricas:

    * moved = aceptado y cambió de celda
    * stopped = no aceptado o no propuso.

Swap: `occ = occNext; occNext = oldOcc`.

### 3.2 Determinismo (muy recomendado)

Para que secuencial y paralelo sean comparables:

* Conflictos se resuelven por orden determinista (menor ID).
* Aleatoriedad por vehículo/tick se genera de forma determinista sin depender del orden de threads.

**Estrategia recomendada de RNG determinista:**

* No usar un `Random` compartido.
* Generar el “random” con una función determinista `f(seed, vehicleId, tick)` para:

  * decidir giro (y si gira, izquierda/derecha).

## 4. Implementación secuencial

### 4.1 Motor secuencial

* Un loop `for tick in 0..T-1` ejecuta fases A→D.
* Fases B y D recorren `i=0..N-1` en un solo thread.
* La fase C usa estructuras simples (map/array) para winners.

### 4.2 Validaciones internas (debug)

En modo debug (opcional):

* Verificar invariantes al final de cada tick:

  * Cada vehículo ocupa exactamente un slot.
  * Ningún slot tiene dos IDs.
  * No existe mezcla horizontal/vertical en un mismo cell.
    Esto ayuda mucho antes de paralelizar.

## 5. Implementación paralela (paralelización por agentes)

### 5.1 Elección y justificación

Se implementará **paralelización por agentes**:

* El cálculo de propuestas por vehículo es altamente paralelizable (lecturas).
* La escritura se hace a un buffer nuevo y se controla con resolución determinista.

### 5.2 Concurrencia y estructuras

* `ExecutorService` fixed thread pool con `P` hilos (configurable).
* Partición por rangos de vehículos: cada tarea procesa `[start,end)`.

Datos por tick:

* `int[] propTargetCell` (size N)
* `int[] propTargetDir` (size N)
* `boolean[] propCanMove` (size N)
* `int[] propTargetKey` (size N) opcional
* `AtomicIntegerArray winners` size `numCells*4` (inicializada en -1 por tick)

#### Fase B paralela (propuestas)

* Cada thread calcula propuestas para su rango y escribe en arrays de propuestas (sin conflictos porque cada índice i es único).

#### Fase C paralela (winners)

* Para cada i donde `propCanMove[i]=true`:

  * `key = targetCell*4 + targetDir`
  * Actualiza `winners[key] = min(winners[key], i)` usando CAS loop.
* Esto garantiza determinismo: el ganador final es el menor id.

#### Fase D paralela (aplicar)

* `occNext` es `int[]` plain, pre-llenado con -1.
* Cada vehículo escribe en una única posición:

  * Si accepted → `occNext[targetKey] = i`
  * else → `occNext[currentKey] = i`
* No hay colisiones de escritura si el modelo es correcto (por diseño winners y chequeos).

### 5.3 Inicialización paralela (opcional)

La colocación inicial suele ser más simple secuencialmente (es solo una vez). Se deja secuencial.

### 5.4 Paralelización alternativa (no obligatoria)

Dejar documentado como extensión:

* Paralelización espacial (partición de la grilla + halos/fronteras).
  No necesaria para cumplir, salvo que quieras comparar estrategias.

## 6. Semáforos: modos de operación

### 6.1 Periódico (MVP requerido)

Parámetro: `periodK` ticks.

* Si `tick % periodK == 0` → toggle (H_GREEN ↔ V_GREEN).

### 6.2 Adaptativo (opcional recomendado)

Idea: decidir el eje verde según congestión local.

* Medir “cola” horizontal: vehículos esperando en celdas adyacentes que apuntan hacia la intersección (y están detenidos).
* Medir “cola” vertical igual.
* Regla ejemplo:

  * Si `queueH - queueV > threshold` → H_GREEN
  * Si `queueV - queueH > threshold` → V_GREEN
  * Si no → mantener estado actual o alternar con periodo máximo para evitar starvation.

Este modo debe ser configurable y debe mantener determinismo (las colas se calculan del estado actual, no de interleavings).

## 7. Contratos de entrada/salida (CLI y formatos)

### 7.1 Entrada: archivo de rejilla (texto)

* Líneas de igual longitud.
* Solo `.` `+` `#`.
* `+` debe tener conectividad a calles en los 4 vecinos cardinales (arriba/abajo/izq/der) como transitable.

### 7.2 Entrada: parámetros de ejecución (CLI)

Definir una interfaz por línea de comandos, por ejemplo:

* `--grid <path>`
* `--vehicles <N>`
* `--ticks <T>`
* `--seed <long>`
* `--turnProb <0..1>`
* `--mode <seq|par>`
* `--threads <P>` (solo si mode=par)
* `--lightMode <periodic|adaptive>`
* `--period <K>` (solo periodic)
* `--out <path>` (opcional CSV)

### 7.3 Salida: consola (obligatorio)

Imprimir al menos:

* Timestamp inicio secuencial, timestamp fin secuencial, duración.
* Timestamp inicio paralelo, timestamp fin paralelo, duración.
* Métricas del sistema (flujo promedio, detenidos promedio).
* Speedup, eficiencia.

### 7.4 Salida: CSV (recomendado)

* `tick, moved, stopped`
* `summary`: `mode, N, ticks, threads, time_ms, avg_flow, avg_stopped, speedup, efficiency`

## 8. Medición de tiempos (requisito explícito)

### 8.1 Requisito

“La medida de toma de tiempos se debe hacer con **time stamps imprimiéndolos por consola**”.

### 8.2 Implementación recomendada

* Capturar `startInstant = Instant.now()` y `endInstant = Instant.now()`; imprimir ambos en ISO-8601.
* Para duración, usar:

  * `Duration.between(startInstant, endInstant)` **o**
  * `System.nanoTime()` para elapsed preciso.
* Imprimir:

  * `[TIMESTAMP] START ...`
  * `[TIMESTAMP] END ... elapsed=...ms`

**Buenas prácticas Java (para desempeño comparativo):**

* Hacer 1 corrida de “warm-up” (sin medir o descartada) para estabilizar JIT, y luego medir.
* Repetir mediciones (p.ej. 3–5 runs) y reportar promedio (opcional pero recomendado para informe).

## 9. Estrategia de testing

### 9.1 Unit tests (JUnit 5)

* Parser de rejilla:

  * caracteres inválidos
  * mapa no rectangular
  * intersección inválida
  * calle ambigua (si aplica la regla de orientación)
* Reglas de movimiento:

  * avance en calle libre
  * detención por ocupación en mismo carril
  * capacidad 2 (opuestos) permitida
  * bloqueo en rojo al entrar a intersección
  * giro en intersección (con semilla/control determinista)
* Semáforos:

  * periodicidad correcta
  * (opcional) adaptativo responde a colas

### 9.2 Integration tests

* Simulación pequeña con semilla fija:

  * Ejecutar T ticks y verificar:

    * invariantes de ocupación
    * métricas dentro de valores esperables (o snapshot exacto si determinista)
* Comparación secuencial vs paralelo:

  * Misma semilla → mismas métricas finales / mismo estado final (ideal).

### 9.3 Tests de concurrencia (sanidad)

* Ejecutar paralelo con P=2,4,8 sobre varios seeds y asegurar:

  * no excepción
  * invariantes siempre cumplen.

## 10. Plan de experimentos para el informe (5–8 páginas)

### 10.1 Métricas del sistema (comportamiento)

* Variar densidad (N) en una misma rejilla:

  * medir `avg_flow` y `avg_stopped`
  * graficar flujo vs N, detenidos vs N
* Variar periodo de semáforo (K) si es periódico:

  * graficar flujo promedio vs K

### 10.2 Métricas computacionales (desempeño)

* Fijar un escenario “grande” (grid grande, N grande, T grande).
* Medir:

  * tiempo secuencial
  * tiempo paralelo para P=2,4,8,... (según CPU)
  * speedup vs P
  * eficiencia vs P

## 11. Lista de tareas detalladas (granular)

### Fase 0 — Setup

1. Crear proyecto Java (Maven o Gradle).
2. Definir versión Java (17+).
3. Agregar dependencias:

   * JUnit 5 (tests)
   * (Opcional) librería CLI (Picocli) o parseo manual de args

### Fase 1 — Grid I/O y validación

4. Implementar lector del archivo (líneas, width uniforme).
5. Parsear símbolos a `CellType`.
6. Implementar validación:

   * solo símbolos permitidos
   * rectangularidad
   * conectividad de `+` (cruce)
   * orientación de `.` (si se aplica)
7. Construir lista de intersecciones y mapeo cell→intersectionId.

### Fase 2 — Modelo y estado

8. Implementar `Direction` con dx/dy y helpers (horizontal/vertical/opuesto).
9. Implementar estructura de ocupación `occ[]` y helpers:

   * get/set slot
   * chequeo eje ocupado en intersecciones
10. Implementar inicialización aleatoria de N vehículos:

* respeta capacidad por dirección
* asigna dirección válida según tipo de celda
* semilla configurable

### Fase 3 — Motor secuencial (MVP funcional)

11. Implementar semáforos periódicos.
12. Implementar lógica de propuesta de movimiento (secuencial).
13. Implementar resolución de conflictos determinista.
14. Implementar aplicación con doble buffer.
15. Implementar `MetricsCollector` (moved/stopped por tick, promedios).
16. Logs en consola + timestamps (inicio/fin).
17. Export opcional CSV por tick.

### Fase 4 — Motor paralelo (por agentes)

18. Definir estructura de propuestas (`propTargetCell/Dir/CanMove`).
19. Implementar fase de propuestas en paralelo con thread pool.
20. Implementar winners con `AtomicIntegerArray` (min id) en paralelo.
21. Implementar aplicación en paralelo con `occNext`.
22. Validar invariantes y comparación con secuencial bajo misma semilla.
23. Logs + timestamps para paralelo.

### Fase 5 — Benchmarking y speedup/eficiencia

24. Implementar runner que ejecute:

* secuencial y paralelo sobre el mismo escenario
* calcule speedup y eficiencia

25. (Opcional) repetir runs, promediar y exportar summary CSV.

### Fase 6 — Testing y robustez

26. Unit tests de parser/validador.
27. Unit tests de semáforo periódico.
28. Unit tests de movimiento y conflicto.
29. Integration test de corrida pequeña determinista.
30. Integration test: secuencial vs paralelo.

### Fase 7 — Informe y gráficas

31. Generar datos CSV para:

* flujo/detenidos vs tiempo
* speedup/eficiencia vs hilos

32. Construir gráficas (herramienta externa) e incorporarlas al informe.
33. Redactar informe (5–8 páginas) con:

* modelo, reglas, validación
* estrategia paralela
* metodología experimental
* resultados y análisis
