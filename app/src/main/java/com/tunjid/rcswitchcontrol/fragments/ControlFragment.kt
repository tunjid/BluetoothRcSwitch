/*
 * MIT License
 *
 * Copyright (c) 2019 Adetunji Dahunsi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tunjid.rcswitchcontrol.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.ViewModelProviders
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.bottomsheet.setupForBottomSheet
import com.google.android.material.tabs.TabLayout
import com.tunjid.rcswitchcontrol.App
import com.tunjid.rcswitchcontrol.R
import com.tunjid.rcswitchcontrol.abstractclasses.BaseFragment
import com.tunjid.rcswitchcontrol.activities.MainActivity
import com.tunjid.rcswitchcontrol.activities.MainActivity.Companion.topInset
import com.tunjid.rcswitchcontrol.broadcasts.Broadcaster
import com.tunjid.rcswitchcontrol.data.ZigBeeCommandArgs
import com.tunjid.rcswitchcontrol.data.persistence.Converter.Companion.serialize
import com.tunjid.rcswitchcontrol.dialogfragments.ZigBeeArgumentDialogFragment
import com.tunjid.rcswitchcontrol.services.ClientNsdService
import com.tunjid.rcswitchcontrol.services.ServerNsdService
import com.tunjid.rcswitchcontrol.viewmodels.ControlViewModel
import com.tunjid.rcswitchcontrol.viewmodels.ControlViewModel.Page
import com.tunjid.rcswitchcontrol.viewmodels.ControlViewModel.Page.*
import com.tunjid.rcswitchcontrol.viewmodels.ControlViewModel.State

class ControlFragment : BaseFragment(), ZigBeeArgumentDialogFragment.ZigBeeArgsListener {

    private lateinit var mainPager: ViewPager
    private lateinit var commandsPager: ViewPager

    private lateinit var connectionStatus: TextView

    private lateinit var viewModel: ControlViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        viewModel = ViewModelProviders.of(this).get(ControlViewModel::class.java)
    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        val root = inflater.inflate(R.layout.fragment_control, container, false)
        val tabs = root.findViewById<TabLayout>(R.id.tabs)
        val commandTabs = root.findViewById<TabLayout>(R.id.command_tabs)
        val bottomSheet = root.findViewById<ViewGroup>(R.id.bottom_sheet)
        val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        val offset = requireContext().resources.getDimensionPixelSize(R.dimen.triple_and_half_margin)

        val calculateTranslation: (slideOffset: Float) -> Float = calculate@{ slideOffset ->
            if (slideOffset == 0F) return@calculate -bottomSheetBehavior.peekHeight.toFloat()

            val multiplier = if (slideOffset < 0) slideOffset else slideOffset
            val height = if (slideOffset < 0) bottomSheetBehavior.peekHeight else bottomSheet.height - offset
            (-height * multiplier) - bottomSheetBehavior.peekHeight
        }

        val onPageSelected: (position: Int) -> Unit = { bottomSheetBehavior.state = if (viewModel.pages[it] == HISTORY) STATE_HALF_EXPANDED else STATE_HIDDEN }

        connectionStatus = root.findViewById(R.id.connection_status)

        mainPager = root.findViewById(R.id.pager)
        commandsPager = root.findViewById(R.id.commands)

        mainPager.adapter = adapter(viewModel.pages, childFragmentManager)
        commandsPager.adapter = commandAdapter(viewModel.keys, childFragmentManager)

        tabs.setupWithViewPager(mainPager)
        commandTabs.setupWithViewPager(commandsPager)

        mainPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) = onPageSelected(position)
        })

        commandsPager.setupForBottomSheet()

        bottomSheet.doOnNextLayout {
            bottomSheet.layoutParams.height = root.height - topInset - resources.getDimensionPixelSize(R.dimen.double_and_half_margin)
        }

        bottomSheetBehavior.setExpandedOffset(offset)
        bottomSheetBehavior.setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                mainPager.translationY = calculateTranslation(slideOffset)
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == STATE_HIDDEN && viewModel.pages[mainPager.currentItem] == HISTORY) bottomSheetBehavior.state = STATE_COLLAPSED
            }
        })

        onPageSelected(mainPager.currentItem)

        return root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        toolBar.setTitle(R.string.switches)
    }

    override fun onStart() {
        super.onStart()
        disposables.add(viewModel.listen(State::class.java).subscribe(this::onPayloadReceived, Throwable::printStackTrace))
        disposables.add(viewModel.connectionState().subscribe(this::onConnectionStateChanged, Throwable::printStackTrace))
    }

    override fun onStop() {
        super.onStop()
        viewModel.onBackground()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_fragment_nsd_client, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.menu_connect)?.isVisible = !viewModel.isConnected
        menu.findItem(R.id.menu_forget)?.isVisible = !ServerNsdService.isServer
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (!viewModel.isBound) return super.onOptionsItemSelected(item)

        when (item.itemId) {
            R.id.menu_connect -> {
                Broadcaster.push(Intent(ClientNsdService.ACTION_START_NSD_DISCOVERY))
                return true
            }
            R.id.menu_forget -> {
                viewModel.forgetService()

                startActivity(Intent(requireActivity(), MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                requireActivity().finish()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun onConnectionStateChanged(text: String) {
        requireActivity().invalidateOptionsMenu()
        connectionStatus.text = resources.getString(R.string.connection_state, text)
    }

    private fun onPayloadReceived(state: State) {
        if (state is State.Commands && state.isNew) {
            Log.i("DIFF BUG", "Calling onPayloadReceived in View")
            commandsPager.adapter?.notifyDataSetChanged()
        }
        if (state is State.History)
            state.commandInfo?.let { ZigBeeArgumentDialogFragment.newInstance(it).show(childFragmentManager, "info") }
    }

    override fun onArgsEntered(args: ZigBeeCommandArgs) =
            viewModel.dispatchPayload(args.key) {
                action = args.command
                data = args.serialize()
            }

    companion object {

        fun newInstance(): ControlFragment {
            val fragment = ControlFragment()
            val bundle = Bundle()

            fragment.arguments = bundle
            return fragment
        }

        fun adapter(pages: List<Page>, fragmentManager: FragmentManager) = object : FragmentStatePagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

            override fun getItem(position: Int): Fragment = when (pages[position]) {
                HOST -> HostFragment.newInstance()
                HISTORY -> RecordFragment.historyInstance()
                DEVICES -> DevicesFragment.newInstance()
            }

            override fun getPageTitle(position: Int): CharSequence? = when (pages[position]) {
                HOST -> App.instance.getString(R.string.host)
                HISTORY -> App.instance.getString(R.string.history)
                DEVICES -> App.instance.getString(R.string.devices)
            }

            override fun getCount(): Int = pages.size
        }

        fun commandAdapter(keys: List<String>, fragmentManager: FragmentManager) = object : FragmentStatePagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

            override fun getItem(position: Int): Fragment = RecordFragment.commandInstance(item(position))

            override fun getPageTitle(position: Int): CharSequence? = item(position).split(".").last().toUpperCase().removeSuffix("PROTOCOL")

            override fun getCount(): Int = keys.size

            private fun item(index: Int) = keys[index]
        }
    }
}