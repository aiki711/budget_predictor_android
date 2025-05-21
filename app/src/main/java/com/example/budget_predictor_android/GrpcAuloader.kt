import android.content.Context
import android.util.Log
import spendingapi.FederatedClientGrpc
import spendingapi.Spending
import io.grpc.ManagedChannelBuilder
import java.io.File
import java.io.FileInputStream
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel

object GrpcUploader {
    fun uploadCsv(file: File, clientId: String) {
        val channel = ManagedChannelBuilder.forAddress("10.0.2.2", 50051)  // localhost for Android emulator
            .usePlaintext()
            .build()

        val stub = FederatedClientGrpc.newBlockingStub(channel)

        val csvBytes = FileInputStream(file).use { it.readBytes() }

        val request = Spending.DataUploadRequest.newBuilder()
            .setClientId(clientId)
            .setCsvData(ByteString.copyFrom(csvBytes).toString())
            .build()

        val response = stub.uploadLocalData(request)
        println("Upload status: ${'$'}{response.status}")

        channel.shutdown()
    }

    fun getSpendingCsvFile(filesDir: File): File {
        return File(filesDir, "spending.csv")
    }

    fun sendDummyData(context: Context) {
        val host = "10.0.2.2" // Androidエミュレータ → ホストPC
        val port = 50051       // gRPCサーバーのポート

        val channel: ManagedChannel = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext() // TLSなし（開発用）
            .build()

        try {
            val stub = FederatedClientGrpc.newBlockingStub(channel)

            val request = Spending.DataUploadRequest.newBuilder()
                .setClientId("U001")
                .setCsvData("1,2,3,4,5") // 適当なダミー特徴量
                .build()

            val response = stub.uploadLocalData(request)
            Log.d("gRPC", "Response from server: ${response.message}")

        } catch (e: Exception) {
            Log.e("gRPC", "Failed to send data: ${e.message}", e)
        } finally {
            channel.shutdown()
        }
    }

}
