#Requisitos esperados
##Funcionalidad básica
* Controlar las páginas visitadas para evitar repeticiones.
* Obtener las páginas mediante HTTP.

## Tolerancia a fallos
* El sistema debe ser capaz de recuperarse de errores en ejecución.
* En caso de caida de un nodo, las peticiones que estuviese haciendo se envian a otros nodos.
*  Si una tarea no se completa en un determinado tiempo, se reintenta todo el ciclo.
* Persistencia de los datos en caso de caidas de nodos.

##Distribución
* El sistema debe de ser distribuido.
* Añadir y quitar nodos dinámicamente.
* Poder replicar las etapas de procesado.

##Configuración
* Realizar reintento de peticiones configurable.
* Seguir redirecciones hasta un límite configurable.
* Poder usar reglas propias para extraer información.
* Poder añadir facilmente etapas de procesado sobre las páginas obtenidas.
* Poder elegir que enlaces seguir.
* Poder añadir facilmente filtros sobre las peticiones enviadas o recibidas.
