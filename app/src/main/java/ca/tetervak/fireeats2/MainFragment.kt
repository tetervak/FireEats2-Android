package ca.tetervak.fireeats2

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import ca.tetervak.fireeats2.databinding.FragmentMainBinding
import ca.tetervak.fireeats2.model.Restaurant
import ca.tetervak.fireeats2.model.RestaurantAdapter
import ca.tetervak.fireeats2.util.RatingUtil
import ca.tetervak.fireeats2.util.RestaurantUtil
import com.firebase.ui.auth.AuthUI
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class MainFragment : Fragment(), MenuProvider,
        FilterDialogFragment.FilterListener,
        RestaurantAdapter.OnRestaurantSelectedListener {

    lateinit var firestore: FirebaseFirestore
    lateinit var query: Query

    private lateinit var binding: FragmentMainBinding
    private lateinit var filterDialog: FilterDialogFragment
    lateinit var adapter: RestaurantAdapter

    private lateinit var viewModel: MainActivityViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // setup fragment menu
        requireActivity().addMenuProvider(
            this, viewLifecycleOwner, Lifecycle.State.RESUMED
        )

        binding = FragmentMainBinding.inflate(inflater, container, false);
        return binding.root;
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // View model
        viewModel = ViewModelProvider(this).get(MainActivityViewModel::class.java)

        // Enable Firestore logging
        FirebaseFirestore.setLoggingEnabled(true)

        // Firestore
        firestore = Firebase.firestore

        // Get ${LIMIT} restaurants
        query = firestore.collection("restaurants")
                .orderBy("avgRating", Query.Direction.DESCENDING)
                .limit(LIMIT.toLong())

        // RecyclerView
        adapter = object : RestaurantAdapter(query, this@MainFragment) {
            override fun onDataChanged() {
                // Show/hide content if the query returns empty.
                if (itemCount == 0) {
                    binding.recyclerRestaurants.visibility = View.GONE
                    binding.viewEmpty.visibility = View.VISIBLE
                } else {
                    binding.recyclerRestaurants.visibility = View.VISIBLE
                    binding.viewEmpty.visibility = View.GONE
                }
            }

            override fun onError(e: FirebaseFirestoreException) {
                // Show a snackbar on errors
                Snackbar.make(binding.root,
                        "Error: check logs for info.", Snackbar.LENGTH_LONG).show()
            }
        }

        binding.recyclerRestaurants.layoutManager = LinearLayoutManager(context)
        binding.recyclerRestaurants.adapter = adapter

        // Filter Dialog
        filterDialog = FilterDialogFragment()

        binding.filterBar.setOnClickListener { onFilterClicked() }
        binding.buttonClearFilter.setOnClickListener { onClearFilterClicked() }
    }

    public override fun onStart() {
        super.onStart()

        // Apply filters
        onFilter(viewModel.filters)

        // Start listening for Firestore updates
        adapter.startListening()
    }

    public override fun onStop() {
        super.onStop()
        adapter.stopListening()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }


    interface SignInProvider{
       fun startSignIn()
    }
    var signInProvider: SignInProvider? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)

        if(context is SignInProvider){
            signInProvider = context
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_add_items -> {
                onAddItemsClicked()
                true
            }
            R.id.menu_sign_out -> {
                AuthUI.getInstance().signOut(requireContext())
                signInProvider?.startSignIn()
                true
            }
            else -> false
        }
    }

    private fun onFilterClicked() {
        // Show the dialog containing filter options
        filterDialog.show(childFragmentManager, FilterDialogFragment.TAG)
    }

    private fun onClearFilterClicked() {
        filterDialog.resetFilters()

        onFilter(Filters.default)
    }

    override fun onRestaurantSelected(restaurant: DocumentSnapshot) {
        // Go to the details page for the selected restaurant
        val action = MainFragmentDirections.actionMainFragmentToRestaurantDetailFragment(restaurant.id)

        findNavController().navigate(action)
    }

    override fun onFilter(filters: Filters) {
        // Construct query basic query
        var query: Query = firestore.collection("restaurants")

        // Category (equality filter)
        if (filters.hasCategory()) {
            query = query.whereEqualTo(Restaurant.FIELD_CATEGORY, filters.category)
        }

        // City (equality filter)
        if (filters.hasCity()) {
            query = query.whereEqualTo(Restaurant.FIELD_CITY, filters.city)
        }

        // Price (equality filter)
        if (filters.hasPrice()) {
            query = query.whereEqualTo(Restaurant.FIELD_PRICE, filters.price)
        }

        // Sort by (orderBy with direction)
        if (filters.hasSortBy()) {
            query = query.orderBy(filters.sortBy.toString(), filters.sortDirection)
        }

        // Limit items
        query = query.limit(LIMIT.toLong())

        // Update the query
        adapter.setQuery(query)

        // Set header
        binding.textCurrentSearch.text = HtmlCompat.fromHtml(filters.getSearchDescription(requireContext()),
                HtmlCompat.FROM_HTML_MODE_LEGACY)
        binding.textCurrentSortBy.text = filters.getOrderDescription(requireContext())

        // Save filters
        viewModel.filters = filters
    }




    private fun onAddItemsClicked() {
        // Add a bunch of random restaurants
        val batch = firestore.batch()
        for (i in 0..9) {
            val restRef = firestore.collection("restaurants").document()

            // Create random restaurant / ratings
            val randomRestaurant = RestaurantUtil.getRandom(requireContext())
            val randomRatings = RatingUtil.getRandomList(randomRestaurant.numRatings)
            randomRestaurant.avgRating = RatingUtil.getAverageRating(randomRatings)

            // Add restaurant
            batch.set(restRef, randomRestaurant)

            // Add ratings to subcollection
            for (rating in randomRatings) {
                batch.set(restRef.collection("ratings").document(), rating)
            }
        }

        batch.commit().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Write batch succeeded.")
            } else {
                Log.w(TAG, "write batch failed.", task.exception)
            }
        }
    }



    companion object {

        private const val TAG = "MainActivity"

        private const val LIMIT = 50
    }
}
