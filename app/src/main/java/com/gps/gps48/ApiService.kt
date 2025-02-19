import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.Call
import retrofit2.Response

data class InventoryRequest(val vin: String, val coordinates: String)
data class ApiResponse(val status: String, val message: String)

interface ApiService {
    @POST("phone_update_inventory.php")
    fun updateInventory(@Body request: InventoryRequest): Response<ApiResponse>
}
