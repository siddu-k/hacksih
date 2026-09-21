package com.sriox.vasateysec

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.sriox.vasateysec.databinding.ActivityAiChatBinding
import com.sriox.vasateysec.utils.LlamaBridge
import com.sriox.vasateysec.utils.SituationSummarizer
import com.sriox.vasateysec.utils.SmolLM2Helper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AiChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiChatBinding
    private val chatMessages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    
    // We will track if the model is loaded successfully
    private var isModelLoaded = false
    private var modelFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        checkLocalModelStatus()
        
        binding.btnBack.setOnClickListener { finish() }
        
        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(chatMessages)
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@AiChatActivity)
            adapter = chatAdapter
        }
    }

    private fun checkLocalModelStatus() {
        val smolFile = SmolLM2Helper.getModelFile(this)
        modelFile = smolFile

        isModelLoaded = true
        binding.etChatInput.isEnabled = true
        binding.btnSendChat.isEnabled = true
        if (SmolLM2Helper.isModelDownloaded(this)) {
            addMessage("Hello! I am SahAi, your personal AI assistant running 100% locally on your phone. Ask me anything!", isUser = false)
            Log.d("AiChat", "SmolLM2 model found: ${smolFile.absolutePath}")
            // Pre-warm model in memory in background so first message has zero delay
            lifecycleScope.launch(Dispatchers.IO) {
                SmolLM2Helper.warmUpModel(this@AiChatActivity)
            }
        } else {
            addMessage("Hello! SahAi local AI chat is ready to download. Go to Settings and tap 'Download Model' (~258 MB) to enable high-speed offline AI chat.", isUser = false)
        }
    }

    private fun sendMessage(text: String) {
        if (!isModelLoaded) return

        addMessage(text, isUser = true)
        binding.etChatInput.text.clear()
        
        lifecycleScope.launch {
            try {
                // Add thinking indicator
                val thinkingMsg = ChatMessage("...", isUser = false, isThinking = true)
                chatMessages.add(thinkingMsg)
                chatAdapter.notifyItemInserted(chatMessages.size - 1)
                binding.rvChat.scrollToPosition(chatMessages.size - 1)

                // Run local inference off the main thread
                val response = generateLocalAiResponse(text)
                
                // Remove thinking indicator
                if (chatMessages.isNotEmpty() && chatMessages.last().isThinking) {
                    chatMessages.removeAt(chatMessages.size - 1)
                    chatAdapter.notifyItemRemoved(chatMessages.size)
                }

                addMessage(response, isUser = false)
            } catch (t: Throwable) {
                Log.e("AiChat", "Error in sendMessage: ${t.message}", t)
                if (chatMessages.isNotEmpty() && chatMessages.last().isThinking) {
                    chatMessages.removeAt(chatMessages.size - 1)
                    chatAdapter.notifyItemRemoved(chatMessages.size)
                }
                addMessage("SahAi: An error occurred while processing your message. Please try again.", isUser = false)
            }
        }
    }

    /**
     * SmolLM2 360M Local AI Inference Generator
     */
    private suspend fun generateLocalAiResponse(prompt: String): String {
        val cleanPrompt = prompt.trim()
        return withContext(Dispatchers.IO) {
            try {
                // Execute SmolLM2 360M local inference with cached in-memory context
                val smolResponse = SmolLM2Helper.generateChatResponse(this@AiChatActivity, cleanPrompt)
                if (smolResponse.isNotBlank()) {
                    smolResponse
                } else {
                    "SahAi: Ready to help. Please ask any question regarding safety, emergency actions, or navigation."
                }
            } catch (t: Throwable) {
                Log.e("AiChat", "SmolLM2 inference error: ${t.message}", t)
                "SahAi offline: ${t.localizedMessage ?: "Unexpected error"}. Try asking again?"
            }
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        chatMessages.add(ChatMessage(text, isUser))
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        binding.rvChat.scrollToPosition(chatMessages.size - 1)
    }

    data class ChatMessage(val text: String, val isUser: Boolean, val isThinking: Boolean = false)

    class ChatAdapter(private val messages: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val container: LinearLayout = view.findViewById(R.id.messageContainer)
            val card: MaterialCardView = view.findViewById(R.id.messageCard)
            val text: TextView = view.findViewById(R.id.tvMessageText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = messages[position]
            holder.text.text = msg.text
            
            val params = holder.card.layoutParams as LinearLayout.LayoutParams
            if (msg.isUser) {
                holder.container.gravity = Gravity.END
                params.gravity = Gravity.END
                holder.card.setCardBackgroundColor(holder.itemView.context.getColor(R.color.violet))
                holder.text.setTextColor(holder.itemView.context.getColor(android.R.color.white))
            } else {
                holder.container.gravity = Gravity.START
                params.gravity = Gravity.START
                holder.card.setCardBackgroundColor(holder.itemView.context.getColor(R.color.card_elevated))
                holder.text.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
            }
            holder.card.layoutParams = params
        }

        override fun getItemCount() = messages.size
    }
}
