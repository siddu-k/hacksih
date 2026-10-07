// Supabase Configuration
const SUPABASE_URL = 'https://pfgvazrkvfaixmfonvmd.supabase.co';
const SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBmZ3ZhenJrdmZhaXhtZm9udm1kIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEzOTA2NjAsImV4cCI6MjEwNjk2NjY2MH0.NYejq2f66erlWH3Zg7CdPkI_wkLs4t65exhRwc_4Nuo';

// Initialize Supabase client
// Using a unique name to avoid conflict with the library itself
const supabaseClient = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    auth: {
        persistSession: false // Avoid "Tracking Prevention" blocks for storage access since we don't need auth here
    }
});

// Fetch all alerts from database (Fresh alerts from last 1 hour, deduplicated by user)
async function fetchAlerts(statusFilter = 'all') {
    try {
        console.log('Fetching fresh alerts...');

        // Calculate timestamp for 1 hour ago
        const oneHourAgo = new Date();
        oneHourAgo.setHours(oneHourAgo.getHours() - 1);

        let query = supabaseClient
            .from('alert_history')
            .select('*')
            .gte('created_at', oneHourAgo.toISOString())
            .order('created_at', { ascending: false });

        // Apply status filter if not 'all'
        if (statusFilter !== 'all') {
            if (statusFilter === 'receiving' || statusFilter === 'received') {
                query = query.in('status', ['acknowledged', 'resolved']);
            } else {
                query = query.eq('status', statusFilter);
            }
        }

        const { data, error } = await query;
        if (error) throw error;

        if (data) {
            // Filter to show only the latest alert per user (deduplication)
            const latestAlerts = data.reduce((acc, alert) => {
                const existingIndex = acc.findIndex(a => a.user_id === alert.user_id);
                if (existingIndex === -1) {
                    acc.push(alert);
                }
                return acc;
            }, []);

            return latestAlerts;
        }
        return [];
    } catch (error) {
        console.error('Error fetching alerts:', error);
        return [];
    }
}

// Subscribe to real-time updates
function subscribeToAlerts(onUpdate) {
    return supabaseClient
        .channel('alert_changes')
        .on(
            'postgres_changes',
            {
                event: '*',
                schema: 'public',
                table: 'alert_history',
            },
            (payload) => {
                console.log('Real-time update received:', payload);
                onUpdate(payload);
            }
        )
        .subscribe();
}

// Fetch users with location data
async function fetchUsersWithLocation() {
    try {
        const { data, error } = await supabaseClient
            .from('users')
            .select('id, name, email, phone, last_latitude, last_longitude, last_location_updated_at')
            .not('last_latitude', 'is', null)
            .not('last_longitude', 'is', null);

        if (error) throw error;
        return data || [];
    } catch (error) {
        console.error('Error fetching users with location:', error);
        return [];
    }
}

// Calculate distance between two coordinates (Haversine formula)
function calculateDistance(lat1, lon1, lat2, lon2) {
    const R = 6371; // Earth's radius in km
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c; // Distance in km
}

// Find nearby users within radius (default 5km)
async function findNearbyUsers(alertLat, alertLon, radiusKm = 5, excludeUserId = null) {
    const allUsers = await fetchUsersWithLocation();

    const nearbyUsers = allUsers
        .map(user => ({
            ...user,
            distance: calculateDistance(alertLat, alertLon, user.last_latitude, user.last_longitude)
        }))
        .filter(user => user.distance <= radiusKm && user.id !== excludeUserId)
        .sort((a, b) => a.distance - b.distance);

    return nearbyUsers;
}

// Format time ago
function formatTimeAgo(timestamp) {
    const now = new Date();
    const alertTime = new Date(timestamp);
    const diffMs = now - alertTime;
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return 'just now';
    if (diffMins < 60) return `${diffMins} minute${diffMins > 1 ? 's' : ''} ago`;
    if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`;
    return `${diffDays} day${diffDays > 1 ? 's' : ''} ago`;
}

// Format date time
function formatDateTime(timestamp) {
    const date = new Date(timestamp);
    return date.toLocaleString('en-US', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: true
    });
}
