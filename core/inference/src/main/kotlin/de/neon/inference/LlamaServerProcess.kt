package de.neon.inference

import android.content.Context
import de.neon.platform.DeviceMemory
import de.neon.platform.MemoryReading
import de.neon.router.Capability
import de.neon.platform.NeonLog
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Stellt sicher, dass ein Server läuft, der ein bestimmtes Modell bedient.
 *
 * Als Schnittstelle, damit Tests gegen einen von Hand gestarteten Server laufen können —
 * ohne Android, ohne Prozessverwaltung.
 */
interface ServerSupervisor {
    /**
     * @param model das Modell selbst und nicht nur seine Kennung.
     *
     *   Der Server braucht mehrere Angaben daraus: die Kosten je Token im
     *   Schlüssel-Wert-Speicher (73728 beim 4-B-Modell, 57344 beim 1.7B), von denen die
     *   Kontextgröße abhängt, und ob das Modell zum Schlussfolgern gedacht ist. Diese
     *   Aufzählung wuchs; ab drei Einzelwerten ist der Spezifikation selbst mitzugeben
     *   einfacher als sie stückweise auseinanderzunehmen.
     * @param projector die Projektordatei eines Bildmodells, sonst `null`.
     * @return ein Client für das Modell, oder `null`, wenn kein Server bereitsteht.
     */
    suspend fun clientFor(
        model: de.neon.router.ModelSpec,
        file: File,
        projector: File? = null,
    ): LlamaServerClient?

    suspend fun shutdown()

    /**
     * Wie es dem Server in diesem Augenblick geht.
     *
     * **Wozu.** Bricht eine Antwort mitten im Strom ab, meldet OkHttp
     * `unexpected end of stream on http://127.0.0.1:18080/`. Diese Meldung sagt, *dass* die
     * Gegenseite weg ist, und nichts darüber, *warum*. Dabei entscheidet genau das über die
     * nächste Runde:
     *
     *  - **Prozess tot** → Speichermangel oder Absturz. Dann hilft ein kleineres Modell oder
     *    ein kleineres Kontextfenster.
     *  - **Prozess lebt** → die Verbindung brach ab, obwohl gerechnet wird. Ein anderer
     *    Fehler, der eine andere Antwort braucht.
     *
     * Gefragt wird erst beim Scheitern, nicht laufend — der Aufruf liest `/proc/meminfo`.
     */
    fun zustand(): ServerZustand = ServerZustand(lebt = null)

    /**
     * Wird während des Ladens regelmäßig gerufen.
     *
     * Der Grund ist ein Fehler, der hier gemacht wurde: Die Startfrist wurde von 60 auf 329
     * Sekunden angehoben, weil 60 zu knapp waren — ohne dafür zu sorgen, dass in dieser Zeit
     * etwas zu sehen ist. Damit dauerte es fünfeinhalb Minuten, bis ein Fehlschlag überhaupt
     * sichtbar wurde. Wer nach zwei Minuten aufgibt, sieht in der Zwischenzeit nichts, was
     * ihm sagt, ob überhaupt etwas passiert.
     */
    var onLoadingProgress: ((LoadingProgress) -> Unit)?
        get() = null
        set(_) = Unit
}

/**
 * Der Zustand des Servers, wie er sich von außen feststellen lässt.
 *
 * Die Felder sind genau die, die einen Abbruch eingrenzen. Bei einem Abschuss durch das
 * System sind sie die einzige Spur, die es je geben wird: `llama-server` bekommt SIGKILL und
 * kann selbst nichts mehr sagen.
 */
data class ServerZustand(
    /**
     * Ob der Serverprozess noch läuft.
     *
     * `null` heißt **nicht wissen**, nicht „lebt" oder „tot". Ein Supervisor, der auf einen
     * von Hand gestarteten Server zeigt, besitzt den Prozess nicht und kann über ihn nichts
     * aussagen. `false` zu melden wäre eine Behauptung, `true` eine Vermutung — und aus einer
     * Vermutung wird im Protokoll binnen eines Tages eine Tatsache.
     */
    val lebt: Boolean?,
    /** Die Kontextgröße, mit der der Server läuft; `0` wenn unbekannt. */
    val kontextGroesse: Int = 0,
    /** Die letzte aussagekräftige Ausgabezeile des Servers, falls es eine gab. */
    val letzteZeile: String? = null,
    /** Der freie Speicher **in diesem Moment** — nicht der beim Laden. */
    val speicher: MemoryReading = MemoryReading(0, 0),
) {
    /** Alles in einer Zeile, wie es in die Protokolldatei gehört. */
    fun describe(): String = buildList {
        add(
            when (lebt) {
                false -> "Serverprozess tot"
                true -> "Serverprozess lebt"
                null -> "Serverprozess unbekannt"
            }
        )
        if (kontextGroesse > 0) add("Kontext $kontextGroesse")
        add(speicher.describe())
        letzteZeile?.let { add("letzte Serverzeile: $it") }
    }.joinToString(" · ")
}

/** Wie weit das Laden ist. */
data class LoadingProgress(
    val elapsedMillis: Long,
    val budgetMillis: Long,
    /** Die letzte aussagekräftige Zeile des Servers, falls es eine gab. */
    val lastLine: String? = null,
)

/**
 * Startet und überwacht den mitgelieferten `llama-server` auf dem Telefon.
 *
 * **Warum ein eigenes Programm statt einer Bibliothek.** Android entpackt beim Installieren
 * alle Dateien aus `jniLibs`, deren Name auf `lib*.so` passt, in ein Verzeichnis, aus dem
 * ausgeführt werden darf — anders als das beschreibbare Datenverzeichnis der App, wo die
 * W^X-Regel das Ausführen verbietet. Deshalb heißt das Programm `libllama-server.so`,
 * obwohl es kein Bibliothek ist, und deshalb steht `extractNativeLibs="true"` im Manifest.
 *
 * **Warum ein Modell je Serverlauf.** llama-server kann mehrere Modelle über einen
 * Router-Modus verwalten, schaltet den aber auf Android bewusst ab: Er bräuchte dafür
 * Kindprozesse, die dort als nicht unterstützt gelten. Neon startet den Server deshalb mit
 * genau einem Modell und startet ihn beim Wechsel neu. Die Hysterese in der Auswahl-Policy
 * vermeidet solche Wechsel ohnehin, wo es geht — und weil die Gewichte per `mmap` aus dem
 * Seitencache kommen, kostet ein Neustart weit weniger als ein Kaltstart.
 */
class ProcessServerSupervisor(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
    contextSize: Int = DEFAULT_CONTEXT_SIZE,
    private val threads: Int = defaultThreads(),
) : ServerSupervisor {

    /**
     * Die gewünschte Größe des Kontextfensters — die **Obergrenze**, nicht die Ansage.
     *
     * Änderbar, weil der richtige Wert vom Gerät und vom Gebrauch abhängt: Wer Anhänge
     * benutzt, braucht mehr; wer nur kurz etwas fragt, verschenkt damit Arbeitsspeicher und
     * Zeit. Eine Änderung wirkt beim nächsten Serverstart — der laufende Server wird dafür
     * beendet, denn die Größe steht beim Start fest und lässt sich nicht nachjustieren.
     *
     * Beim Start wird der Wunsch gegen den freien Speicher geprüft und notfalls gesenkt.
     * Ein gespeicherter Wunsch von 16384 hat die App auf einem Gerät mit 1,6 GB freiem
     * Speicher sechsmal erschlagen — und ein Wunsch, der die App tötet, ist keiner.
     * [laufenderKontext] sagt, was tatsächlich benutzt wird.
     */
    @Volatile
    var contextSize: Int = contextSize.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE)
        set(value) {
            val neu = value.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE)
            if (neu == field) return
            field = neu
            // Nicht sofort neu starten: Der nächste Aufruf tut das ohnehin, und ein
            // Neustart mitten in einer laufenden Antwort wäre das Gegenteil von hilfreich.
            servingModelId = null
        }

    /**
     * Die Kontextgröße, mit der der Server wirklich läuft.
     *
     * Kann kleiner sein als [contextSize], wenn der Speicher nicht mehr hergab. Für die
     * Anzeige: Wer 4096 statt der eingestellten 16384 bekommt, soll das sehen können.
     */
    @Volatile
    var laufenderKontext: Int = contextSize.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE)
        private set

    private var process: Process? = null
    @Volatile
    private var servingModelId: String? = null
    private var client: LlamaServerClient? = null
    private val stopping = AtomicBoolean(false)

    /** Wann der Server zuletzt etwas ausgegeben hat. Grundlage der Startfrist. */
    private val lastOutputAt = AtomicLong(0)

    /** Die zuletzt gesehene aussagekräftige Zeile — für die Fortschrittsmeldung. */
    @Volatile
    private var lastMeaningfulLine: String? = null

    override var onLoadingProgress: ((LoadingProgress) -> Unit)? = null

    private val baseUrl: String = "http://127.0.0.1:$port"

    /**
     * Die Merkdatei für den laufenden Ladeversuch.
     *
     * Im Datenverzeichnis und nicht im Cache: Android darf den Cache jederzeit leeren, und
     * eine Spur, die genau dann verschwindet, wenn es knapp wird, wäre nutzlos.
     */
    private val attemptLog = LoadAttemptLog(File(context.filesDir, "ladeversuch.txt"))

    /** Wo das Programm nach der Installation liegt. */
    private val binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    val isAvailable: Boolean get() = binary.canExecute()

    val isRunning: Boolean get() = process?.isAlive == true

    val currentModelId: String? get() = servingModelId

    /**
     * Hier ist [ServerZustand.lebt] eine echte Auskunft: Dieser Supervisor hat den Prozess
     * selbst gestartet und hält ihn in der Hand.
     *
     * Der Speicher wird **jetzt** gelesen und nicht der Wert vom Laden benutzt. Der
     * Unterschied ist der ganze Zweck: Beim Laden waren 1,7 GB frei, und die Frage ist, was
     * davon übrig war, als es krachte.
     */
    override fun zustand(): ServerZustand = ServerZustand(
        lebt = isRunning,
        kontextGroesse = laufenderKontext,
        letzteZeile = lastMeaningfulLine,
        speicher = DeviceMemory.read(),
    )

    override suspend fun clientFor(
        model: de.neon.router.ModelSpec,
        file: File,
        projector: File?,
    ): LlamaServerClient? {
        val modelId = model.id
        val kvBytesPerToken = model.kvBytesPerToken
        if (servingModelId == modelId && isRunning) {
            client?.let { if (it.isHealthy()) return it }
        }

        stopProcess()
        if (!isAvailable) {
            NeonLog.e(TAG, "llama-server fehlt unter ${binary.absolutePath}")
            return null
        }
        if (!file.isFile) {
            NeonLog.e(TAG, "Modelldatei fehlt: ${file.absolutePath}")
            return null
        }

        stopping.set(false)

        // Was der letzte Anlauf nicht mehr sagen konnte.
        //
        // Wird der App-Prozess von Android wegen Speichermangels beendet, geschieht das mit
        // SIGKILL — ohne Handler, ohne Protokollzeile. Auf dem Gerät starb er sechsmal
        // hintereinander beim Laden, und dazwischen stand nichts als die nächste
        // Startmeldung. Diese Zeile ist die Nachricht von damals.
        attemptLog.verlorenerVersuch()?.let { NeonLog.e(TAG, it.describeAsLost()) }

        // Vor dem eigenen Server erst die aufräumen, die ihre App nicht überlebt haben.
        // Jeder von ihnen hält das Modell offen; aus einem Tod wird sonst eine
        // Kettenreaktion, weil jeder neue Anlauf einen weiteren daneben legt.
        killOrphans()

        // Einmal je Serverstart: was der Prozessor kann und wie viel Luft noch ist.
        // Zusammen mit der `system_info`-Zeile, die llama-server gleich danach schreibt,
        // ergibt das den Vergleich, auf den es ankommt — was das Gerät könnte gegen das,
        // was die Binärdatei benutzt.
        val speicher = DeviceMemory.read()
        NeonLog.i(TAG, "${CpuFeatures.describe()} · ${speicher.describe()}")

        // Die Kontextgröße an den Speicher anpassen, der wirklich da ist.
        //
        // Genau hier wurde die App sechsmal erschlagen: Kontext 16384 verlangt 1152 MB
        // Schlüssel-Wert-Speicher, und das ist anonymer Speicher, den der Kernel nicht
        // verdrängen kann. Bei 1600 MB freien war Neon damit der dickste Brocken im System.
        val gewuenscht = contextSize
        val benutzt = passendeKontextgroesse(speicher.availableBytes, gewuenscht, kvBytesPerToken)

        // **Die Meldung muss den richtigen Grund nennen.** Sie tat es nicht: Im
        // Geräteprotokoll stand vierzehnmal „Kontext auf 16384 statt 18432 begrenzt … RAM:
        // 10,5 von 14,8 GB frei" — und kein einziges Mal war der Speicher die Ursache. Der
        // Regler stand auf 18432, und das ist keine der Stufen, die es gibt; gerundet wird
        // auf 16384. Eine Meldung, die den freien Speicher im selben Atemzug nennt, liest
        // sich aber wie Speichermangel, und danach sucht man an der falschen Stelle.
        //
        // Deshalb wird beides getrennt bestimmt: Was gäbe die Stufung her, und was gibt der
        // Speicher her? Die Antwort auf die erste Frage ist eine Rundung, die auf die zweite
        // eine Begrenzung.
        val gerundet = naechsteStufe(gewuenscht)
        if (benutzt != gewuenscht) {
            val grund = if (benutzt < gerundet) {
                "der Speicher reicht dafür nicht — " +
                    "${benutzt.toLong() * kvBytesPerToken / MB} MB statt " +
                    "${gewuenscht.toLong() * kvBytesPerToken / MB} MB für den " +
                    "Schlüssel-Wert-Speicher, ${speicher.describe()}"
            } else {
                "$gewuenscht ist keine der möglichen Stufen " +
                    "(${KONTEXT_STUFEN.joinToString(", ")}), also die nächstkleinere. " +
                    "Am Speicher liegt es nicht: ${speicher.describe()}"
            }
            NeonLog.i(TAG, "Kontext auf $benutzt statt $gewuenscht — $grund")
        }
        laufenderKontext = benutzt

        val modelBytes = file.length() + (projector?.length() ?: 0L)
        attemptLog.beginnen(
            LoadAttempt(
                startedAtMillis = System.currentTimeMillis(),
                modelName = file.name,
                modelBytes = modelBytes,
                contextSize = benutzt,
                kvBytes = benutzt.toLong() * kvBytesPerToken,
                memory = speicher,
            )
        )

        val started = runCatching { launch(file, projector, model) }.getOrElse {
            NeonLog.e(TAG, "llama-server ließ sich nicht starten", it)
            attemptLog.gelungen()
            return null
        }
        process = started

        val newClient = LlamaServerClient(baseUrl)
        // Die Dateigröße statt der Angabe aus der Registry: Sie beschreibt, was tatsächlich
        // gelesen werden muss, und stimmt auch bei einer anderen Quantisierung.
        if (!waitForHealth(newClient, modelBytes)) {
            stopProcess()
            // Auch ein berichteter Fehlschlag ist ein zurückgekommener Versuch: Die
            // Merkdatei ist nur für die Fälle da, in denen niemand mehr berichten konnte.
            attemptLog.gelungen()
            return null
        }

        attemptLog.gelungen()
        servingModelId = modelId
        client = newClient
        return newClient
    }

    /**
     * Beendet `llama-server`-Prozesse, die von einem früheren App-Prozess übrig sind.
     *
     * Erlaubt ist das, weil sie dieselbe Benutzerkennung tragen wie Neon selbst — es sind
     * die eigenen Kindprozesse, nur ohne Elternteil.
     */
    private fun killOrphans() {
        val pfad = binary.absolutePath
        val eigene = android.os.Process.myPid()
        val opfer = OrphanedServers.toKill(OrphanedServers.readProcesses(), pfad, eigene)
        if (opfer.isEmpty()) return

        NeonLog.i(TAG, "beende ${opfer.size} übrig gebliebene llama-server: $opfer")
        opfer.forEach { pid -> runCatching { android.os.Process.killProcess(pid) } }
    }

    private fun launch(modelFile: File, projector: File?, spec: de.neon.router.ModelSpec): Process {
        val command = buildList {
            add(binary.absolutePath)
            add("--model"); add(modelFile.absolutePath)
            add("--alias"); add(modelFile.nameWithoutExtension)
            add("--host"); add("127.0.0.1")
            add("--port"); add(port.toString())
            add("--ctx-size"); add(laufenderKontext.toString())
            add("--threads"); add(threads.toString())

            // Ein Telefon, ein Nutzer. llama-server legt sonst vier Bearbeitungsplätze an,
            // die hier nie gleichzeitig gebraucht werden.
            add("-np"); add("1")

            // Der Schlüssel-Wert-Speicher ist der Teil, der mit dem Kontextfenster wächst
            // — bei diesem Modell 144 KB je Token. Auf acht Bit komprimiert halbiert sich
            // das, und ein Fenster von 16384 kostet damit gut ein Gigabyte statt zwei
            // ein Viertel. Gegen einen echten Server geprüft, einschließlich einer
            // vollständigen Antwort.
            add("--cache-type-k"); add(KV_CACHE_TYPE)
            add("--cache-type-v"); add(KV_CACHE_TYPE)

            // Ohne den Projektor kann ein Bildmodell keine Bilder ansehen. Er ist die
            // zweite Hälfte des Modells und wird getrennt importiert.
            if (projector != null) {
                add("--mmproj"); add(projector.absolutePath)
            }

            // Die Chat-Vorlage aus der GGUF-Datei benutzen, statt eine zu raten. Ohne das
            // sieht ein Modell mit ungewöhnlicher Vorlage einen falsch formatierten Prompt.
            add("--jinja")

            // Denken abschalten — außer beim Denkmodell.
            //
            // `--reasoning` steht in der Voreinstellung auf `auto`, und das heißt: Was die
            // Vorlage vorsieht. Qwen3 sieht Denken vor. Damit beginnt jede Antwort mit einem
            // `<think>`-Block, der Token kostet, ungefiltert in der Sprechblase landet und
            // vorgelesen wird.
            //
            // Auf diesem Gerät entstehen rund anderthalb Token je Sekunde. Vierzig Token
            // Selbstgespräch sind dort keine Feinheit, sondern der Unterschied zwischen
            // einer Antwort und keiner. Das Denkmodell ist genau dafür da und behält es.
            if (!spec.supports(Capability.REASONING)) {
                add("--reasoning"); add("off")
            }
            // Die eingebaute Weboberfläche wird nie benutzt und kostet nur Speicher.
            add("--no-webui")
        }

        val started = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .start()
        drainOutput(started)
        return started
    }

    /**
     * Die Ausgabe des Servers muss gelesen werden.
     *
     * Läuft der Ausgabepuffer voll, blockiert der Server beim Schreiben und bleibt stehen —
     * ein Fehler, der sich als "hängt gelegentlich" äußert und schwer zu finden ist.
     */
    private fun drainOutput(process: Process) {
        thread(name = "neon-llama-log", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { zeile ->
                        // Jede Zeile ist ein Lebenszeichen. Daran hängt die Startfrist:
                        // Ein Server, der noch redet, arbeitet noch. Deshalb zählt hier
                        // wirklich jede Zeile, auch die belanglosen.
                        lastOutputAt.set(System.currentTimeMillis())

                        if (istAussagekraeftig(zeile)) {
                            lastMeaningfulLine = zeile.trim()
                            // Nur das Wesentliche in die Protokolldatei. llama-server
                            // schreibt in der Voreinstellung hunderte Zeilen beim Laden;
                            // ungefiltert ertrinkt darin alles, was man später lesen will.
                            NeonLog.i(TAG, zeile)
                        } else {
                            NeonLog.d(TAG, zeile)
                        }
                    }
                }
            }
        }
    }

    /**
     * Wartet, bis der Server antwortet.
     *
     * **Warum nicht einfach eine feste Frist.** Hier stand einmal eine Minute. Auf dem Gerät
     * brauchte das Alltagsmodell 59,548 Sekunden — Neon gab 450 Millisekunden später auf und
     * erschlug einen Server, der gerade fertig geworden war. Der nächste Versuch begann von
     * vorn und scheiterte genauso: kein Wackeln, sondern ein dauerhafter Ausfall.
     *
     * Eine größere feste Zahl wäre nur eine bessere Vermutung. Die Ladezeit hängt an der
     * Modellgröße (2,5 GB gegen 5 GB), am Seitencache (kalt gegen warm) und an Androids
     * Dateiverschlüsselung, die beim ersten Lesen jede Seite entschlüsselt — gemessene
     * 42 MB/s statt der Geschwindigkeit des Flash-Speichers.
     *
     * Deshalb zwei Bedingungen statt einer:
     *
     *  - **Stille.** Solange der Server Ausgabezeilen schreibt, arbeitet er. Erst wenn er
     *    [SILENCE_TIMEOUT_MILLIS] lang schweigt *und* nicht antwortet, gilt er als hängend.
     *  - **Obergrenze nach Größe.** Als zweite Sicherung für den Fall, dass er stumm hängt:
     *    eine Frist, die mit der Modellgröße wächst statt für alle Modelle gleich zu raten.
     */
    private fun waitForHealth(client: LlamaServerClient, modelBytes: Long): Boolean {
        val budget = startupBudgetMillis(modelBytes)
        val begonnen = System.currentTimeMillis()
        lastOutputAt.set(begonnen)

        // Der zuletzt gemeldete Grund. Nur bei einer Änderung protokolliert — bei alle
        // 250 ms füllte "Connection refused" sonst die ganze Datei.
        var gemeldeterGrund: String? = null

        NeonLog.i(TAG, "warte auf llama-server, Frist ${budget / 1000} s für ${modelBytes / MB} MB")

        while (System.currentTimeMillis() - begonnen < budget) {
            if (stopping.get()) return false
            if (process?.isAlive != true) {
                NeonLog.e(TAG, "llama-server hat sich beim Start beendet")
                return false
            }
            if (client.isHealthy()) {
                NeonLog.i(TAG, "llama-server bereit nach ${System.currentTimeMillis() - begonnen} ms")
                return true
            }

            // Warum es nicht klappte. Beim Laden steht hier „Connection refused", und das
            // ist in Ordnung. Steht dort etwas anderes, ist genau das die Antwort auf die
            // Frage, warum Neon nicht loslegt — und sie darf nicht wieder verloren gehen.
            val grund = client.lastHealthFailure
            if (grund != null && grund != gemeldeterGrund) {
                gemeldeterGrund = grund
                NeonLog.i(TAG, "llama-server antwortet noch nicht: $grund")
            }

            val vergangen = System.currentTimeMillis() - begonnen
            onLoadingProgress?.invoke(
                LoadingProgress(
                    elapsedMillis = vergangen,
                    budgetMillis = budget,
                    // Ohne eine Serverzeile ist der Grund das Einzige, was zu sagen ist —
                    // und besser als eine Zahl, die stumm hochläuft.
                    lastLine = lastMeaningfulLine ?: grund,
                )
            )

            val stille = System.currentTimeMillis() - lastOutputAt.get()
            if (stille > SILENCE_TIMEOUT_MILLIS) {
                NeonLog.e(TAG, "llama-server schweigt seit $stille ms und antwortet nicht${weil(grund)}")
                return false
            }

            Thread.sleep(HEALTH_POLL_MILLIS)
        }

        // Der letzte Blick, bevor eine Minute Arbeit weggeworfen wird. Genau dieser eine
        // Aufruf hätte den Ausfall auf dem Gerät verhindert.
        if (client.isHealthy()) {
            NeonLog.i(TAG, "llama-server war knapp doch noch rechtzeitig fertig")
            return true
        }

        NeonLog.e(TAG, "llama-server war nach $budget ms nicht bereit${weil(client.lastHealthFailure)}")
        return false
    }

    /** Hängt den Grund an eine Fehlermeldung, wenn es einen gibt. */
    private fun weil(grund: String?): String = grund?.let { " — $it" }.orEmpty()

    override suspend fun shutdown() = stopProcess()

    private fun stopProcess() {
        stopping.set(true)
        client?.close()
        client = null
        servingModelId = null
        process?.let { running ->
            running.destroy()
            if (!running.waitFor(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly()
            }
        }
        process = null
    }

    companion object {
        private const val TAG = "NeonLlamaServer"

        /**
         * Das Programm heißt `lib*.so`, weil der Installer nur solche Dateien in ein
         * ausführbares Verzeichnis entpackt. Es ist trotzdem ein normales Programm.
         */
        const val BINARY_NAME = "libllama-server.so"

        /** Hoher Port, nur an localhost gebunden. */
        const val DEFAULT_PORT = 18_080

        /**
         * Voreinstellung: 16384 Token.
         *
         * Rund 12.000 Wörter — genug für einen Gesprächsverlauf samt mehrerer Fundstellen
         * aus Anhängen. Mit komprimiertem Cache kostet das etwa 1,1 GB, zusammen mit dem
         * Alltagsmodell knapp 3,5 GB und damit deutlich weniger, als das Gerät hergibt.
         */
        const val DEFAULT_CONTEXT_SIZE = 16_384

        /** Darunter passt kaum eine Seite Text — dann lohnt der ganze Aufwand nicht. */
        const val MIN_CONTEXT_SIZE = 4_096

        /**
         * Obergrenze. Darüber wird es auf 16 GB eng, sobald nebenher etwas anderes läuft,
         * und jeder Prompt braucht spürbar länger, weil mehr Text durchgerechnet wird.
         */
        const val MAX_CONTEXT_SIZE = 32_768

        /**
         * Wie der Schlüssel-Wert-Speicher abgelegt wird.
         *
         * `q8_0` halbiert ihn gegenüber `f16` bei einem Qualitätsverlust, den man in einer
         * Antwort nicht bemerkt — anders als bei `q4_0`, wo längere Zusammenhänge sichtbar
         * leiden.
         */
        const val KV_CACHE_TYPE = "q8_0"

        /**
         * Wie viel Arbeitsspeicher ein Token im Schlüssel-Wert-Speicher belegt.
         *
         * Gerechnet aus der Modellkonfiguration von Qwen3 4B: 36 Schichten, 8 KV-Köpfe,
         * head_dim 128, Schlüssel und Wert je ein Byte je Element bei `q8_0` — macht
         * 2 * 36 * 8 * 128 = 73728 Byte. Bei `f16` das Doppelte.
         *
         * Gilt für das Alltagsmodell. Ein anderes Modell hat andere Zahlen; für die
         * Anzeige neben dem Regler ist das die richtige Größenordnung.
         */
        const val KV_BYTES_PER_TOKEN = 73_728L

        /**
         * Wie viele Rechenfäden llama-server bekommt.
         *
         * Hier stand fest verdrahtet `8` — eine Zahl, die aus dem Datenblatt eines
         * bestimmten Prozessors stammte und nie geprüft wurde. Mehr Fäden als verfügbare
         * Kerne machen es messbar langsamer statt schneller, weil die Fäden einander vom
         * Kern verdrängen; und wie viele Kerne Android der App zugesteht, weiß nur das
         * Gerät.
         *
         * [Runtime.availableProcessors] meldet unter Android die Kerne, die dem Prozess
         * gerade zustehen. Nach oben begrenzt, weil llama.cpp über acht Fäden kaum noch
         * Durchsatz gewinnt und vor allem heizt — und Wärme kostet auf einem Telefon
         * innerhalb einer Minute mehr, als die Fäden einbringen.
         */
        fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(MIN_THREADS, MAX_THREADS)

        const val MIN_THREADS = 4
        const val MAX_THREADS = 8

        private const val MB = 1024L * 1024
        private const val GB = 1024L * MB

        /**
         * Grundzeit für den Start, unabhängig von der Modellgröße.
         *
         * Mindestens so lang wie [SILENCE_TIMEOUT_MILLIS]. Sonst schlüge bei einem kleinen
         * Modell immer die Uhr zu, bevor das Schweigen überhaupt auffallen könnte — und die
         * Uhr ist genau das Kriterium, das sich als falsch erwiesen hat.
         */
        const val STARTUP_BASE_MILLIS = 120_000L

        /**
         * Zuschlag je Gigabyte Modelldatei.
         *
         * Gemessen wurden auf dem S26 Ultra rund 42 MB/s beim ersten Laden, also etwa
         * 25 Sekunden je Gigabyte. Mit 90 Sekunden liegt die Frist mehr als dreifach
         * darüber — reichlich, aber das ist der Sinn: Zu früh aufzugeben kostet die ganze
         * Antwort, zu spät nur Geduld in einem Fall, der ohnehin schiefgeht.
         */
        const val STARTUP_PER_GIGABYTE_MILLIS = 90_000L

        /**
         * So lange darf der Server schweigen, bevor er als hängend gilt.
         *
         * Beim Laden schreibt llama.cpp regelmäßig Zeilen. Anderthalb Minuten ohne ein
         * einziges Wort heißen: Er kommt nicht mehr voran.
         *
         * Kürzer als [STARTUP_BASE_MILLIS], damit dieses Kriterium in jedem Fall zuerst
         * greift. Ein hängender Server soll am Schweigen auffallen und nicht an der Uhr —
         * die Uhr ist genau das, was sich als falsch erwiesen hat.
         */
        const val SILENCE_TIMEOUT_MILLIS = 90_000L

        /**
         * Ob eine Ausgabezeile in die Protokolldatei gehört.
         *
         * `llama-server` läuft mit Verbosität 3 und schreibt beim Laden hunderte Zeilen.
         * Ungefiltert füllen sie die Datei, verdrängen das Interessante und sprengen jeden
         * Weg, sie weiterzugeben. Behalten wird, was eine Frage beantwortet: Was wird
         * geladen, ist es fertig, hört es zu, ging etwas schief.
         */
        fun istAussagekraeftig(zeile: String): Boolean {
            val klein = zeile.lowercase()
            return MELDENSWERT.any { klein.contains(it) }
        }

        private val MELDENSWERT = listOf(
            "load_model",
            "model loaded",
            "listening",
            "error",
            "failed",
            "warning: ",
            "out of memory",
            "oom",
            "n_ctx",
            "kv cache",
            "kv self",

            // Ab hier die Zeilen, die über die Geschwindigkeit Auskunft geben. Sie fehlten,
            // als sich herausstellte, dass Neon mit 0,71 Token je Sekunde antwortete: Die
            // Zahlen standen zwar in der Ausgabe des Servers, aber nicht in der Datei, die
            // man weitergeben kann.
            //
            // `system_info` nennt die Rechenbefehle, die die Binärdatei benutzt — genau
            // die Auskunft, an der sich die Ursache ablesen ließ.
            "system_info",
            // `print_timing` bringt Token je Sekunde für jede einzelne Antwort mit.
            "print_timing",
            "n_threads",
        )

        /** Die Frist für ein Modell dieser Größe. */
        fun startupBudgetMillis(modelBytes: Long): Long =
            STARTUP_BASE_MILLIS + STARTUP_PER_GIGABYTE_MILLIS * modelBytes / GB

        /**
         * Die größte Kontextgröße, die in den freien Speicher passt.
         *
         * **Der Fehler, den das verhindert.** Der Schlüssel-Wert-Speicher ist der einzige
         * Posten, der mit dem Kontextfenster wächst *und* nicht verdrängt werden kann. Die
         * Modellgewichte liegen per `mmap` als Dateiseiten im Cache — die darf der Kernel
         * jederzeit wegnehmen und neu einlesen. Anonymer Speicher nicht.
         *
         * Auf dem Gerät standen 1600 MB frei, und Kontext 16384 verlangte davon 1152 MB.
         * Damit war Neon der dickste Brocken im System, und Androids Low-Memory-Killer nimmt
         * genau den — sechsmal hintereinander, ohne eine Zeile Erklärung.
         *
         * **Ein Drittel** als Obergrenze, weil der Rest gebraucht wird: Rechenpuffer des
         * Servers (einige hundert Megabyte), die App selbst, die Sprachausgabe, und etwas
         * Luft für alles, was Android sonst noch vorhat. Zwei Drittel wären knapp
         * gerechnet, die Hälfte wäre eine Wette.
         *
         * @param verfuegbar freier Speicher in Byte; `0` heißt „nicht gemessen".
         * @param obergrenze der eingestellte Wunsch.
         * @return die zu benutzende Größe, nie größer als [obergrenze].
         */
        fun passendeKontextgroesse(
            verfuegbar: Long,
            obergrenze: Int,
            kvBytesPerToken: Long = KV_BYTES_PER_TOKEN,
        ): Int {
            val grenze = obergrenze.coerceIn(MIN_CONTEXT_SIZE, MAX_CONTEXT_SIZE)

            // Ohne Messung nichts ändern. Eine fehlende Auskunft darf keine stille
            // Verschlechterung auslösen — sonst kürzt Neon auf jedem Gerät, dessen
            // /proc/meminfo sich nicht lesen lässt, grundlos das Kontextfenster.
            if (verfuegbar <= 0) return grenze

            val erlaubt = verfuegbar / KV_ANTEIL
            return KONTEXT_STUFEN
                .filter { it <= grenze && it.toLong() * kvBytesPerToken <= erlaubt }
                .maxOrNull()
                // Passt nicht einmal die kleinste Stufe, bleibt trotzdem sie: Unter
                // MIN_CONTEXT_SIZE passt kaum eine Seite Text, und ein Fenster, in dem
                // nichts mehr steht, ist keine Rettung. Dann soll der Abschuss sichtbar
                // werden statt in einer unbrauchbaren Einstellung zu verschwinden.
                ?: MIN_CONTEXT_SIZE
        }

        /**
         * Die Stufen, zwischen denen gewählt wird — dieselben wie am Regler.
         *
         * **„Dieselben wie am Regler" stimmte nicht.** Der Regler rastete auf 4096, 11264,
         * 18432, 25600 und 32768; hier standen 4096, 8192, 16384 und 32768. Drei der fünf
         * Raststellen gab es also gar nicht, und wer eine davon wählte, bekam still die
         * nächstkleinere. Wer den Regler von 18432 auf 25600 schob, bekam beide Male 16384 —
         * eine Einstellung, die sich bewegen ließ und nichts bewirkte.
         *
         * Jetzt liest der Regler diese Liste. Zwei Listen mit derselben Bedeutung waren eine
         * Liste zu viel.
         */
        val KONTEXT_STUFEN = listOf(4_096, 8_192, 16_384, 32_768)

        /**
         * Die größte Stufe, die den Wunsch nicht überschreitet — ohne den Speicher zu fragen.
         *
         * Getrennt von [passendeKontextgroesse], damit sich Rundung und Speicherbegrenzung
         * unterscheiden lassen. Genau diese Unterscheidung fehlte in der Meldung.
         */
        fun naechsteStufe(wunsch: Int): Int =
            KONTEXT_STUFEN.filter { it <= wunsch }.maxOrNull() ?: MIN_CONTEXT_SIZE

        /**
         * Wie viel des freien Speichers der Schlüssel-Wert-Speicher höchstens belegen darf.
         *
         * **Hier stand ein Drittel, und das war zu großzügig.** Gemessen auf dem Gerät:
         *
         * | frei | Drittel | gewählt | KV | Ergebnis |
         * |---|---|---|---|---|
         * | 1,7 GB | 580 MB | 8192 | 576 MB | **vom System getötet** |
         * | 1,9 GB | 648 MB | 8192 | 576 MB | überlebt |
         *
         * Vier Megabyte unter der eigenen Grenze — das war keine Entscheidung, das war
         * Glück, und beim ersten Versuch ging es schief. Was in der Rechnung fehlte, sind
         * die Rechenpuffer des Servers: Sie sind ebenfalls anonym und ebenfalls nicht
         * verdrängbar, und für ein 4-B-Modell gehen sie in die hunderte Megabyte.
         *
         * Mit einem Fünftel wären in beiden Fällen 4096 gewählt worden. Als Teiler
         * geschrieben, damit die Rechnung in Ganzzahlen bleibt.
         */
        const val KV_ANTEIL = 5L
        private const val HEALTH_POLL_MILLIS = 250L
        private const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L
    }
}
