package ca.sleepnoise

/**
 * Tracks which noise-loop generation jobs are still wanted. Every new request invalidates older
 * jobs, so a slow job for a setting the user has already moved away from is never played.
 */
class LoopBaker<T> {
    private var version = 0
    var baked: T? = null; private set
    private var baking: T? = null

    /** Returns a job number to generate [shape], or null if nothing needs generating. */
    @Synchronized fun request(shape: T): Int? {
        if (shape == baking) return null              // already being generated and still wanted
        version++                                     // anything older is now stale
        if (shape == baked) { baking = null; return null }   // back to what's playing: just cancel
        baking = shape
        return version
    }

    @Synchronized fun isCurrent(job: Int) = job == version

    /** Marks [job] finished. Returns false if it was superseded, in which case its loop must be dropped. */
    @Synchronized fun complete(job: Int, shape: T): Boolean {
        if (job != version) return false
        baked = shape; baking = null
        return true
    }
}
