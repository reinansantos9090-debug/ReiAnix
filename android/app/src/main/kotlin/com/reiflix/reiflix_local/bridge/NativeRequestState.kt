package com.reiflix.reiflix_local.bridge
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class NativeRequestState {
    enum class OperationState {
        RECEIVED, QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED, TIMEOUT
    }

    data class LifecycleAction(val action: String, val requestId: String?)
    data class RequestSnapshot(
        val action: String,
        val state: OperationState,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private val supportedActions=setOf(
        "select_tree","scan_tree","verify_tree","release_tree","scan_media_store",
        "request_media_access","open_broad_storage_settings","open_storage_settings","check_storage_access","open_settings","hide_settings","open_organize",
        "scan_all_storage","extract_thumbnail","cancel_scan","cancel_player_transition","google_sign_in","google_sign_out","play"
    )
    private val seenRequestIds=LinkedHashSet<String>()
    private val requestSnapshots=LinkedHashMap<String, RequestSnapshot>()
    var lastHandledRequestId:String?=null
        private set
    var pendingLifecycleAction:String?=null
        private set
    var pendingLifecycleRequestId:String?=null
        private set
    private var lastConsumedLifecycleRequest:LifecycleAction?=null
    private var preferences:SharedPreferences?=null

    fun bind(context:Context){
        preferences=context.applicationContext.getSharedPreferences("reiflix_native_request_state",Context.MODE_PRIVATE)
        val serialized=preferences?.getString("seen_request_ids",null)
        if(!serialized.isNullOrBlank()) restoreSeenRequestIds(serialized)
        val serializedStates=preferences?.getString("request_states",null)
        if(!serializedStates.isNullOrBlank()) restoreRequestStates(serializedStates)
        val persistedLast=preferences?.getString("last_handled_request_id",null)?.trim().orEmpty()
        if(persistedLast.isNotEmpty()) lastHandledRequestId=persistedLast
        val persistedAction=preferences?.getString("pending_lifecycle_action",null)
        val persistedRequestId=preferences?.getString("pending_lifecycle_request_id",null)
        if(pendingLifecycleAction==null && isSupportedAction(persistedAction)){
            pendingLifecycleAction=persistedAction
            pendingLifecycleRequestId=persistedRequestId?.trim()?.takeIf{it.isNotEmpty()}
        }
    }

    private fun persist(){
        val editor = preferences?.edit()
            ?.putString("seen_request_ids",seenRequestIdsState())
            ?.putString("last_handled_request_id",lastHandledRequestId)
            ?.putString("pending_lifecycle_action",pendingLifecycleAction)
            ?.putString("pending_lifecycle_request_id",pendingLifecycleRequestId)
            ?.putString("request_states",requestStatesState())
        val committed = editor?.commit() ?: true
        if (!committed) android.util.Log.e("NativeRequestState", "Failed to durably persist lifecycle/request state")
    }

    @Synchronized
    fun acceptRequest(requestId:String?, action:String? = null, createdAt:Long = System.currentTimeMillis()):Boolean{
        val id=requestId?.trim().orEmpty()
        if(id.isEmpty())return false
        if(!seenRequestIds.add(id))return false
        while(seenRequestIds.size>64)seenRequestIds.iterator().apply{next();remove()}
        lastHandledRequestId=id
        if(isSupportedAction(action)) {
            requestSnapshots[id]=RequestSnapshot(action!!, OperationState.RECEIVED, createdAt, System.currentTimeMillis())
            while(requestSnapshots.size>64)requestSnapshots.remove(requestSnapshots.keys.first())
        }
        persist()
        return true
    }

    @Synchronized
    fun markOperationState(requestId:String?, action:String?, state:OperationState, now:Long = System.currentTimeMillis()):Boolean{
        val id=requestId?.trim().orEmpty()
        if(id.isEmpty())return false
        val previous=requestSnapshots[id]
        val resolvedAction=(action?.trim().orEmpty()).ifBlank { previous?.action ?: "unknown" }
        val createdAt=previous?.createdAt ?: now
        requestSnapshots[id]=RequestSnapshot(resolvedAction,state,createdAt,now)
        while(requestSnapshots.size>64)requestSnapshots.remove(requestSnapshots.keys.first())
        persist()
        return true
    }

    fun operationState(requestId:String?):OperationState? =
        requestSnapshots[requestId?.trim().orEmpty()]?.state

    fun requestSnapshot(requestId:String?):RequestSnapshot? =
        requestSnapshots[requestId?.trim().orEmpty()]

    fun seenRequestIdsState():String=JSONArray(seenRequestIds.toList()).toString()

    fun requestStatesState():String {
        val array=JSONArray()
        requestSnapshots.forEach { (id,snapshot) ->
            array.put(JSONObject()
                .put("requestId",id)
                .put("action",snapshot.action)
                .put("state",snapshot.state.name)
                .put("createdAt",snapshot.createdAt)
                .put("updatedAt",snapshot.updatedAt))
        }
        return array.toString()
    }

    @Synchronized
    fun restoreRequestStates(serialized:String?) {
        if(serialized.isNullOrBlank())return
        runCatching {
            val a=JSONArray(serialized)
            requestSnapshots.clear()
            for(i in 0 until a.length()){
                val item=a.optJSONObject(i) ?: continue
                val id=item.optString("requestId").trim()
                val action=item.optString("action").trim()
                val state=item.optString("state").trim()
                if(id.isBlank() || !isSupportedAction(action)) continue
                val parsed=runCatching { OperationState.valueOf(state) }.getOrNull() ?: continue
                requestSnapshots[id]=RequestSnapshot(action,parsed,item.optLong("createdAt",0L),item.optLong("updatedAt",0L))
            }
        }.onFailure {
            android.util.Log.e("NativeRequestState","Failed to restore request states",it)
        }
        while(requestSnapshots.size>64)requestSnapshots.remove(requestSnapshots.keys.first())
    }

    fun restoreSeenRequestIds(serialized:String?){
        if(serialized.isNullOrBlank())return
        seenRequestIds.clear()
        runCatching{
            val a=JSONArray(serialized)
            for(i in 0 until a.length()){
                a.optString(i).trim().takeIf{it.isNotEmpty()}?.let(seenRequestIds::add)
            }
        }
        while(seenRequestIds.size>64)seenRequestIds.iterator().apply{next();remove()}
    }

    @Synchronized
    fun queueLifecycleAction(action:String,requestId:String?=null):Boolean{
        if(!isSupportedAction(action) || pendingLifecycleAction!=null)return false
        pendingLifecycleAction=action
        pendingLifecycleRequestId=requestId?.trim()?.takeIf{it.isNotEmpty()}
        markOperationState(requestId, action, OperationState.QUEUED)
        persist()
        return true
    }

    @Synchronized
    fun consumeLifecycleRequest():LifecycleAction?{
        val action=pendingLifecycleAction ?: return null
        val result=LifecycleAction(action,pendingLifecycleRequestId)
        pendingLifecycleAction=null
        pendingLifecycleRequestId=null
        lastConsumedLifecycleRequest=result
        persist()
        return result
    }

    fun consumeLifecycleAction():String?=consumeLifecycleRequest()?.action
    fun consumedLifecycleRequestId():String?=lastConsumedLifecycleRequest?.requestId

    fun restore(lastRequestId:String?,pendingAction:String?,pendingRequestId:String?=null){
        val restoredId=lastRequestId?.trim().orEmpty()
        if(restoredId.isNotEmpty()) seenRequestIds.add(restoredId)
        if(restoredId.isNotEmpty()) lastHandledRequestId=restoredId
        if(isSupportedAction(pendingAction)){
            pendingLifecycleAction=pendingAction
            pendingLifecycleRequestId=pendingRequestId?.trim()?.takeIf{it.isNotEmpty()}
        }
        while(seenRequestIds.size>64)seenRequestIds.iterator().apply{next();remove()}
        persist()
    }

    companion object{
        fun isSupportedAction(action:String?):Boolean=
            action!=null&&NativeRequestState().supportedActions.contains(action)
    }
}
