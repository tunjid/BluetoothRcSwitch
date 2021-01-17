package com.tunjid.rcswitchcontrol.services

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.jakewharton.rx.replayingShare
import com.rcswitchcontrol.protocols.CommsProtocol
import com.rcswitchcontrol.protocols.io.ConsoleWriter
import com.tunjid.androidx.communications.nsd.NsdHelper
import com.tunjid.rcswitchcontrol.common.filterIsInstance
import com.tunjid.rcswitchcontrol.common.onErrorComplete
import com.tunjid.rcswitchcontrol.common.serialize
import com.tunjid.rcswitchcontrol.common.toLiveData
import com.tunjid.rcswitchcontrol.di.AppBroadcaster
import com.tunjid.rcswitchcontrol.di.AppBroadcasts
import com.tunjid.rcswitchcontrol.di.AppContext
import com.tunjid.rcswitchcontrol.models.Broadcast
import com.tunjid.rcswitchcontrol.nsd.protocols.ProxyProtocol
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.processors.PublishProcessor
import io.reactivex.rxkotlin.Flowables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject

enum class Status {
    Initialized, Registered, Error, Stopped
}

sealed class Input {
    object Restart : Input()
}

data class State(
    val numClients: Int = 0,
    val numWrites: Long = 0L,
    val status: Status = Status.Initialized
)

private data class Response(val data: String) : Input()

private data class Once<T>(private val item: T) {
    private var gotten = false
    fun get(): T? = if (gotten) null else item.also { gotten = true }
}

private sealed class Output {
    sealed class Server(val status: Status) : Output() {
        data class Initialized(val helper: NsdHelper) : Server(Status.Initialized)
        data class Registered(val service: NsdServiceInfo, val socket: ServerSocket) : Server(Status.Registered)
        data class Error(val service: NsdServiceInfo, val reason: Int) : Server(Status.Error)
    }

    sealed class Client : Output() {
        sealed class Status : Client() {
            data class Added(val port: Int, val writer: PrintWriter) : Status()
            data class Dropped(val port: Int, val writer: PrintWriter) : Status()
        }

        data class Request(val port: Int, val data: String) : Client()
    }
}

class ServerViewModel @Inject constructor(
    @AppContext context: Context,
    broadcaster: AppBroadcaster,
    broadcasts: AppBroadcasts
) : ViewModel() {

    val state: LiveData<State>

    private val disposable = CompositeDisposable()
    private val processor = PublishProcessor.create<Input>()
    private val protocol = ProxyProtocol(context = context, printWriter = ConsoleWriter {
        processor.onNext(Response(it))
    })

    init {
        val serverOutputs: Flowable<Output.Server> = processor
            .filterIsInstance<Input.Restart>()
            .startWith(Input.Restart)
            .switchMap { context.registerServer(ServerNsdService.serviceName) }
            .replayingShare()
            .takeUntil(broadcasts.filterIsInstance<Broadcast.ServerNsd.Stop>())

        val registrations: Flowable<Output.Server.Registered> = serverOutputs
            .filterIsInstance<Output.Server.Registered>()
            .replayingShare()

        val clientOutputs: Flowable<Output.Client> = registrations
            .map(Output.Server.Registered::socket)
            .switchMap(ServerSocket::clients)
            .flatMap(protocol::outputs)
            .replayingShare()

        val clients: Flowable<Set<PrintWriter>> = clientOutputs
            .filterIsInstance<Output.Client.Status>()
            .scan(setOf<PrintWriter>()) { set, status ->
                when (status) {
                    is Output.Client.Status.Added -> set + status.writer
                    is Output.Client.Status.Dropped -> set - status.writer
                }
            }
            .replayingShare()

        val writes: Flowable<Once<String>> = clientOutputs
            .filterIsInstance<Output.Client.Request>()
            .map(Output.Client.Request::data)
            .mergeWith(processor
                .filterIsInstance<Response>()
                .map(Response::data)
            )
            .map(::Once)

        val backingState = Flowables.combineLatest(
            clients.map(Set<PrintWriter>::size),
            writes.scan(0) { oldCount, _ -> oldCount + 1 },
            serverOutputs.map(Output.Server::status)
                .concatWith(Flowable.just(Status.Stopped)),
            ::State
        )
            .replayingShare()

        state = backingState.toLiveData()

        // Kick off
        Flowables.combineLatest(
            clients,
            writes
        )
            .subscribe { (writers, write) ->
                write.get()?.let { data ->
                    writers.forEach { it.println(data) }
                }
            }
            .addTo(disposable)

        registrations
            .subscribe { registration ->
                ServerNsdService.isServer = true
                ServerNsdService.serviceName = registration.service.serviceName
                ClientNsdService.lastConnectedService = registration.service.serviceName

                broadcaster(Broadcast.ClientNsd.StartDiscovery())
            }
            .addTo(disposable)
    }

    override fun onCleared() {
        super.onCleared()
        disposable.clear()
    }

    fun accept(input: Input) = processor.onNext(input)
}

private fun Context.registerServer(name: String): Flowable<Output.Server> =
    Flowables.create(BackpressureStrategy.BUFFER) { emitter ->
        val serverSocket = ServerSocket(0)
        NsdHelper.getBuilder(this)
            .setRegisterSuccessConsumer { service ->
                emitter.onNext(Output.Server.Registered(service, serverSocket))
            }
            .setRegisterErrorConsumer { service, error ->
                emitter.onNext(Output.Server.Error(service, error))
                emitter.onComplete()
            }
            .build()
            .let { nsdHelper ->
                emitter.setCancellable(nsdHelper::tearDown)
                emitter.onNext(Output.Server.Initialized(nsdHelper))
                nsdHelper.registerService(serverSocket.localPort, name)
            }
    }

private fun ServerSocket.clients(): Flowable<Socket> =
    Flowable
        .fromCallable { accept() }
        .repeatUntil(::isClosed)
        .doFinally(::close)
        .onErrorComplete()
        .hopSchedulers()

private fun CommsProtocol.outputs(socket: Socket): Flowable<Output.Client> =
    Flowable.defer {
        val outWriter = NsdHelper.createPrintWriter(socket)
        val reader = NsdHelper.createBufferedReader(socket)

        Log.i("TEST", "PINGING")
        // Initiate conversation with client
        outWriter.println(processInput(CommsProtocol.pingAction.value).serialize())

        Flowable.fromCallable<Output.Client> {
            val input = reader.readLine()
            val output = processInput(input).serialize()
            if (output == "Bye.") socket.close()
            Output.Client.Request(port = socket.port, data = output)
        }
            .repeatUntil(socket::isClosed)
            .doFinally(socket::close)
            .onErrorComplete()
            .startWith(Output.Client.Status.Added(port = socket.port, writer = outWriter))
            .concatWith(Flowable.just(Output.Client.Status.Dropped(port = socket.port, writer = outWriter)))
    }
        .onErrorComplete()
        .hopSchedulers()

fun <T> Flowable<T>.hopSchedulers(): Flowable<T> =
    compose { it.subscribeOn(Schedulers.io()).observeOn(Schedulers.io()) }
