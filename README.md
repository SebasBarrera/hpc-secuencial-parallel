# traffic-abm-parallel

Simulación Basada en Agentes (ABM) de tráfico urbano con motor secuencial y paralelo (paralelización por agentes).

## Reporte

- [`report/Reporte.pdf`](report/Reporte.pdf)

## Suposiciones y decisiones (para reproducibilidad)

Este proyecto fija las siguientes decisiones para evitar ambigüedad y asegurar comparabilidad secuencial/paralela:

- **Actualización sincrónica por ticks**: las decisiones del tick se computan leyendo el estado del tick anterior y se aplican con doble buffer (`occ`/`occNext`).
- **Semáforos (MVP)**: modo periódico.
  - Estado inicial: `H_GREEN`.
  - Toggle cuando `tick % period == 0` para `tick > 0` (primer cambio en `tick=period`).
- **Capacidad por celda**: hasta 2 vehículos por celda transitable, representado como `occ[cellIdx*4 + dirIdx]`.
  - Se permiten únicamente direcciones opuestas sobre el mismo eje (E/W o N/S).
  - No se permite mezcla de eje horizontal y vertical simultáneamente dentro de una misma celda.
- **Resolución de conflictos determinista**:
  - Si múltiples vehículos proponen el mismo slot destino `(cellIdx, dirIdx)`, gana el menor `vehicleId`.
  - Adicionalmente, por celda destino se elige determinísticamente un único eje ganador (horizontal o vertical) para evitar mezcla de ejes en la misma celda.
- **Aleatoriedad determinista**: no se usa `Random` compartido.
  - Las decisiones por vehículo/tick (p.ej. giro) se derivan de una función determinista `f(seed, vehicleId, tick)`.
- **Validación de rejilla (GridLoader)**:
  - Solo símbolos `.` `+` `#`.
  - El mapa debe ser rectangular.
  - El borde completo debe ser `#`.
  - `+` representa un cruce de calles perpendiculares: debe existir conectividad horizontal (E o W transitable) y vertical (N o S transitable).
  - Un cruce o giro no puede representarse con `.`. En este MVP, cada `.` debe ser un segmento recto (conectividad de grado 2 en un solo eje).

## Requisitos

- Java 17+
- Maven 3.9+

## Compilar

```bash
mvn clean package
```

El JAR se genera como:

- `target/traffic-abm.jar`

## Ejecutar

```bash
java -jar target/traffic-abm.jar --help
```

### Secuencial

```bash
java -jar target/traffic-abm.jar \
  --grid grids/ejemplo1.txt \
  --vehicles 100 \
  --ticks 1000 \
  --seed 42 \
  --mode seq \
  --period 10 \
  --turnProb 0.2 \
  --out data/ticks_seq.csv
```

### Paralelo

```bash
java -jar target/traffic-abm.jar \
  --grid grids/big.txt \
  --vehicles 1200 \
  --ticks 2000 \
  --seed 42 \
  --mode par \
  --threads 8 \
  --period 10 \
  --turnProb 0.2 \
  --out data/ticks_par_p8.csv
```

### Benchmark (warm-up + repeticiones + summary.csv)

```bash
java -jar target/traffic-abm.jar \
  --benchmark \
  --grid grids/big.txt \
  --vehicles 1200 \
  --ticks 2000 \
  --seed 42 \
  --threads 2,4,8 \
  --reps 3 \
  --period 10 \
  --turnProb 0.2 \
  --out data/summary.csv
```
