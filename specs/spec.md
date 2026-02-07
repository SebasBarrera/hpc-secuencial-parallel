# SPEC — Simulación ABM de Tráfico Urbano con Programación Paralela

**Documento:** spec.txt
**Proyecto:** Simulación Basada en Agentes de Tráfico Urbano con Programación Paralela
**Curso:** Computación Paralela y Simulación
**Fecha:** 31 de enero de 2026
**Estado:** Draft (para revisión)
**Lenguaje objetivo:** Java (preferente)

## 1. Resumen

Se construirá una simulación de tráfico urbano basada en agentes (ABM) sobre una **rejilla bidimensional** leída desde un **archivo de texto externo**. Los **vehículos** (agentes) se mueven por calles e intersecciones con **semaforización**; su interacción local produce fenómenos globales como **congestión**.

El proyecto exige implementar:

1. Un **modelo secuencial** completamente funcional.
2. Una **versión paralela** (al menos una estrategia de paralelización).
3. **Métricas del sistema** (flujo, detenidos) y **métricas computacionales** (tiempos, speedup, eficiencia).
4. **Mediciones de tiempo con timestamps impresos por consola**.
5. Insumos para un **informe técnico (5–8 páginas)** con gráficas de comportamiento y desempeño.

## 2. Objetivos de negocio / académicos

* Demostrar comprensión de **ABM** aplicado a tráfico urbano.
* Validar un enfoque de **paralelización** que mejore rendimiento para simulaciones de tamaño significativo.
* Producir evidencia cuantitativa del comportamiento del sistema (flujo, detenidos).
* Producir evidencia cuantitativa de desempeño (tiempos, speedup, eficiencia).
* Entregar código claro, reproducible y con mediciones confiables.

## 3. Alcance

### En alcance (must)

* Lectura de rejilla desde archivo externo (texto).
* Validación de consistencia mínima del mapa según reglas del enunciado.
* Inicialización de **N vehículos** ubicados aleatoriamente en celdas transitables.
* Simulación por pasos discretos de tiempo:

  1. Actualización de semáforos.
  2. Actualización de vehículos (movimiento / detención / giro en intersecciones con probabilidad).
* Cálculo y reporte de métricas del sistema:

  * Flujo promedio de vehículos.
  * Número de vehículos detenidos (por paso y/o agregado).
* Implementación secuencial completa.
* Implementación paralela de al menos una estrategia:

  * Paralelización espacial **o**
  * Paralelización por agentes.
* Medición de tiempos de ejecución secuencial y paralela e impresión en consola usando **timestamps**.
* Cálculo de speedup y eficiencia.
* Exportación (o impresión) de datos necesarios para construir gráficas en el informe.

### Fuera de alcance (no requerido)

* GUI/visualización en tiempo real (opcional si se desea, pero no requerida).
* Modelos realistas avanzados (aceleración/frenado multi-celda, múltiples carriles por sentido, etc.).
* Integración con mapas reales (GIS).

## 4. Definiciones y glosario

* **Rejilla (grid):** matriz 2D de celdas.
* **Celda transitable:** celda que permite vehículos (`.` o `+`).
* **Calle (`.`):** segmento de vía transitable.
* **Intersección (`+`):** cruce de calles perpendiculares; único lugar con semáforo.
* **Bloque (`#`):** celda no transitable.
* **Agente/vehículo:** entidad autónoma con posición y dirección.
* **Paso de simulación (tick):** unidad de tiempo discreto donde se actualizan semáforos y vehículos.
* **Flujo:** medida del movimiento de vehículos por unidad de tiempo (definida formalmente en métricas).
* **Detenido:** vehículo que no avanza en un tick (por rojo, ocupación, o imposibilidad de avanzar).

## 5. Requisitos funcionales

### RF-1 Lectura de rejilla desde archivo externo

* El programa **debe** leer la configuración espacial desde un archivo de texto.
* No se permite definir la rejilla directamente en el código “como fuente principal de configuración” para ejecución normal.

### RF-2 Símbolos soportados

* `.` segmento de calle transitable
* `+` intersección con semáforo
* `#` espacio no transitable

### RF-3 Validación de consistencia del mapa

El sistema **debe** validar y rechazar (con mensaje de error) mapas que incumplan reglas de consistencia.

Reglas mínimas requeridas:

* Solo se permiten los símbolos `.` `+` `#`.
* Las celdas `#` son intransitables.
* Los vehículos solo pueden estar/moverse por `.` y `+`.
* Una intersección `+` **debe** coincidir con el cruce de dos calles perpendiculares.

> Nota (decisión de especificación): Para hacer la simulación bien definida, se considera inválido un mapa donde existan “cruces” representados con `.` (el cruce debe ser `+`) o donde una `+` no tenga conectividad coherente.

### RF-4 Representación de calles bidireccionales y capacidad por celda

* Todas las calles se asumen **bidireccionales**.
* Cada celda transitable tiene capacidad de **hasta 2 vehículos**, **uno en cada dirección opuesta** (por el mismo eje de la calle).
* No se permite que una celda contenga vehículos en direcciones perpendiculares simultáneamente (para evitar colisiones conceptuales).

### RF-5 Inicialización de agentes

* El número de agentes **N** es un parámetro configurable.
* Los agentes **deben** ubicarse de forma aleatoria en celdas transitables, respetando capacidad y direcciones válidas.
* Cada agente debe tener al menos:

  * Posición (x,y)
  * Dirección de movimiento
  * Estado de avance o detención (por tick)

### RF-6 Semáforos en intersecciones

* Cada intersección `+` posee un semáforo que alterna el paso de vehículos en direcciones perpendiculares.
* El semáforo puede ser:

  * **Periódico** (alternancia cada K ticks), o
  * **Adaptativo** (según congestión local).
* El proyecto **debe** implementar al menos un modo (periódico o adaptativo). (Se recomienda permitir ambos como configuración si es viable.)

### RF-7 Dinámica por paso de tiempo

En cada tick:

1. Los semáforos actualizan su estado.
2. Para cada vehículo:

   * Intenta avanzar a la siguiente celda si:

     * Es transitable,
     * Tiene capacidad disponible en el sentido correspondiente,
     * Y si se trata de ingresar a una intersección, el semáforo lo permite.
   * Se detiene si la siguiente celda está ocupada (en el sentido correspondiente) o el semáforo está en rojo.
   * Si el vehículo está en una intersección, puede cambiar de dirección con cierta probabilidad.

### RF-8 Equidad y determinismo de la simulación

* La simulación **debe** tener un esquema de actualización consistente (evitar que el orden de iteración sesgue el resultado).
* Con una semilla de aleatoriedad fija, el sistema **debe** producir resultados reproducibles (al menos por modo de ejecución).

> Recomendación fuerte para comparabilidad: el modo paralelo debería producir resultados equivalentes al secuencial bajo la misma semilla y reglas de resolución de conflictos.

### RF-9 Métricas del sistema de tráfico

Se deben calcular como mínimo:

* **Flujo promedio de vehículos**:
  Definición requerida: número de vehículos que avanzan por tick, promediado sobre el horizonte de simulación (y opcionalmente normalizado por N).
* **Número de vehículos detenidos**:
  Conteo de vehículos que no avanzan en un tick (por reglas del sistema), reportable por tick y/o como promedio.

### RF-10 Métricas computacionales

Se deben reportar:

* Tiempo de ejecución secuencial (con timestamps de inicio y fin impresos por consola).
* Tiempo de ejecución paralelo (con timestamps de inicio y fin impresos por consola).
* **Speedup** = T_secuencial / T_paralelo
* **Eficiencia** = Speedup / P (P = número de hilos/procesos usados)

### RF-11 Ejecución parametrizable

El sistema debe permitir configurar, como mínimo:

* Ruta del archivo de rejilla.
* Número de agentes N.
* Número de ticks.
* Semilla de aleatoriedad (para reproducibilidad).
* Parámetros del semáforo (p.ej. periodo K si es periódico).
* Probabilidad de giro en intersecciones.
* Modo de ejecución: secuencial o paralelo.
* Si es paralelo: número de hilos.

### RF-12 Salidas para análisis e informe

El sistema debe producir salidas que permitan construir:

* Gráficas de comportamiento del sistema: por ejemplo flujo vs tiempo, detenidos vs tiempo, flujo promedio vs densidad.
* Gráficas de desempeño: tiempo vs N, speedup vs hilos, eficiencia vs hilos.

La salida puede ser:

* Impresión estructurada por consola, y/o
* Archivo(s) de datos (CSV/TSV) para graficar externamente.

## 6. Requisitos no funcionales

### RNF-1 Rendimiento y escalabilidad

* La versión paralela debe mostrar mejora de rendimiento para simulaciones de tamaño significativo.
* Debe poder ejecutarse con tamaños de rejilla y N suficientemente grandes para observar diferencias (según recursos de la máquina de prueba).

### RNF-2 Seguridad de concurrencia

* La implementación paralela no debe presentar condiciones de carrera, corrupción de estado, deadlocks o livelocks.

### RNF-3 Reproducibilidad

* Con semilla fija y mismos parámetros, los resultados deben ser reproducibles.
* Debe ser posible repetir experimentos para obtener métricas comparables.

### RNF-4 Observabilidad

* Deben imprimirse **timestamps** en consola al menos para:

  * Inicio y fin de una corrida secuencial.
  * Inicio y fin de una corrida paralela.
  * Reporte final con métricas.
* Los logs deben ser claros y fáciles de usar para el informe.

### RNF-5 Calidad de código

* Código legible, modular, con responsabilidades claras.
* Pruebas automatizadas para las partes críticas (parser/validador, reglas de movimiento, semáforos, consistencia básica).

## 7. Manejo de errores (comportamiento esperado)

* Archivo inexistente o ilegible: abortar con mensaje claro.
* Mapa con caracteres inválidos: abortar con mensaje claro indicando ubicación si es posible.
* Mapa no rectangular: abortar con mensaje claro.
* Inconsistencias (por ejemplo `+` que no representa cruce): abortar con mensaje claro.
* Parámetros inválidos (N negativo, ticks <= 0, probabilidad fuera de [0,1], hilos <= 0): abortar con mensaje claro.
* Imposibilidad de ubicar N vehículos por falta de capacidad: abortar o reducir N explícitamente (preferible abortar con error explicativo para evitar resultados engañosos).

## 8. Criterios de aceptación (checklist verificable)

### Correctitud funcional

* [ ] El programa lee la rejilla desde archivo externo y no depende de una rejilla hardcodeada para correr.
* [ ] Se validan símbolos y consistencia básica de intersecciones.
* [ ] Se inicializan N vehículos aleatoriamente en celdas transitables cumpliendo capacidad.
* [ ] Se ejecutan ticks siguiendo el orden: semáforos → vehículos.
* [ ] Los vehículos respetan:

  * Movilidad solo por `.` y `+`,
  * Semáforo para entrada a intersección,
  * Detención por ocupación o rojo,
  * Giro probabilístico en intersecciones.
* [ ] Se calculan métricas del sistema (flujo promedio, detenidos) y se reportan.
* [ ] Se implementa un modo secuencial funcional.

### Paralelismo y desempeño

* [ ] Se implementa al menos una estrategia paralela (espacial o por agentes).
* [ ] El modo paralelo produce resultados consistentes (reproducibles con semilla).
* [ ] Se reportan tiempos secuencial y paralelo con timestamps en consola.
* [ ] Se calculan speedup y eficiencia.
* [ ] Se evidencia mejora de rendimiento en escenarios grandes.

### Entregables

* [ ] Código secuencial.
* [ ] Código paralelo.
* [ ] Informe técnico 5–8 páginas.
* [ ] Gráficas de comportamiento del sistema y desempeño computacional.

## 9. Supuestos y decisiones de especificación

Para reducir ambigüedad, se fijan estas decisiones (pueden ajustarse si tu docente indica lo contrario):

* Actualización por ticks es **sincrónica** (se evalúan decisiones con el estado del tick anterior).
* La capacidad “2 vehículos, uno por dirección opuesta” se interpreta como **dos carriles virtuales** por eje (ej. E/W o N/S), no direcciones perpendiculares simultáneas.
* La resolución de conflictos (dos vehículos queriendo la misma celda/sentido) se hace de forma **determinista** (por ejemplo, ganando el de menor ID), para comparabilidad secuencial/paralela.
* El modo de semáforo mínimo implementable es **periódico**; adaptativo es opcional si hay tiempo.