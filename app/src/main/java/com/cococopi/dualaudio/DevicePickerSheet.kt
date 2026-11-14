package com.cococopi.dualaudio

import android.bluetooth.BluetoothProfile
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cococopi.dualaudio.databinding.ItemDeviceBinding
import com.cococopi.dualaudio.databinding.SheetDevicePickerBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet that lists paired audio devices. The activity receives the
 * chosen address through the Fragment Result API.
 */
class DevicePickerSheet : BottomSheetDialogFragment() {

    data class Entry(
        val address: String,
        val name: String,
        val state: Int,
    )

    private var binding: SheetDevicePickerBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = SheetDevicePickerBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewBinding = binding ?: return
        val args = requireArguments()

        val addresses = args.getStringArrayList(ARG_ADDRESSES).orEmpty()
        val names = args.getStringArrayList(ARG_NAMES).orEmpty()
        val states = args.getIntArray(ARG_STATES) ?: intArrayOf()
        val slot = args.getInt(KEY_SLOT, 0)

        val entries = addresses.mapIndexed { index, address ->
            Entry(
                address = address,
                name = names.getOrNull(index) ?: address,
                state = states.getOrNull(index) ?: BluetoothProfile.STATE_DISCONNECTED,
            )
        }

        val adapter = DeviceAdapter(entries) { entry ->
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(KEY_ADDRESS to entry.address, KEY_SLOT to slot),
            )
            dismiss()
        }

        viewBinding.recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        viewBinding.recyclerDevices.adapter = adapter
        viewBinding.recyclerDevices.isVisible = entries.isNotEmpty()
        viewBinding.textEmpty.isVisible = entries.isEmpty()
        viewBinding.btnCancel.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        binding?.recyclerDevices?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private class DeviceAdapter(
        private val entries: List<Entry>,
        private val onClick: (Entry) -> Unit,
    ) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemDeviceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return Holder(binding)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(entries[position], onClick)
        }

        class Holder(private val binding: ItemDeviceBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: Entry, onClick: (Entry) -> Unit) {
                val context = binding.root.context
                binding.textName.text = entry.name
                binding.textStatus.text = when (entry.state) {
                    BluetoothProfile.STATE_CONNECTED ->
                        context.getString(R.string.device_picker_connected)
                    BluetoothProfile.STATE_CONNECTING ->
                        context.getString(R.string.device_picker_connecting)
                    BluetoothProfile.STATE_DISCONNECTING ->
                        context.getString(R.string.device_disconnected)
                    else -> context.getString(R.string.device_picker_paired)
                }
                binding.imageCheck.isVisible = entry.state == BluetoothProfile.STATE_CONNECTED
                binding.root.setOnClickListener { onClick(entry) }
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "device_picker_result"
        const val KEY_ADDRESS = "address"
        const val KEY_SLOT = "slot"

        private const val ARG_ADDRESSES = "addresses"
        private const val ARG_NAMES = "names"
        private const val ARG_STATES = "states"

        fun newInstance(slot: Int, entries: List<Entry>): DevicePickerSheet =
            DevicePickerSheet().apply {
                arguments = bundleOf(
                    KEY_SLOT to slot,
                    ARG_ADDRESSES to ArrayList(entries.map { it.address }),
                    ARG_NAMES to ArrayList(entries.map { it.name }),
                    ARG_STATES to entries.map { it.state }.toIntArray(),
                )
            }
    }
}
