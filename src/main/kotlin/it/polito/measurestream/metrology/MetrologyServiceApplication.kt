package it.polito.measurestream.metrology

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Il sistema qualita' di MeasureStream.
 *
 * sensor-manager resta lo strumento: decodifica, converte, da' a ogni valore la sua incertezza.
 * Questo servizio tiene tutto cio' che serve a dire che quello strumento e' tarato: esemplari,
 * campioni, sessioni, certificati, punti di controllo, conformita', scadenze, documenti.
 * I due si parlano per eventi Kafka e per REST, mai attraverso le tabelle dell'altro.
 */
@SpringBootApplication
class MetrologyServiceApplication

fun main(args: Array<String>) {
	runApplication<MetrologyServiceApplication>(*args)
}
