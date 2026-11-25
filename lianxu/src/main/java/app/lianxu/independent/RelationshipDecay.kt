package app.lianxu.independent

import kotlin.math.exp
import kotlin.math.ln

/** Engine 2 constants and pure formulas; no persisted parameter fields. */
internal object RelationDecay {
    const val ENGINE=8
    // 小星已定（2026-11-11）：小事14天、大事60天减半；心结不自己变淡；心情仍按小时平复。
    const val SMALL_DAYS=14.0
    const val LASTING_DAYS=60.0
    const val MERGE_DAYS=30.0
    const val UNDO_DAYS=90.0
    const val MOMENT_LIMIT=40
    const val KNOT_MERGE=.02
    const val MAX_ENTRIES=200
    const val HAPPY_H=12.0
    const val DISAPPOINT_H=7.0
    const val ANGRY_H=6.0
    const val HURT_H=24.0
    const val LOW_H=36.0
    const val HOLD_H=2.0
    const val POSITIVE_WEIGHT=0.44
    const val BIG_EVENT=0.25
    const val GRUDGE_SHARE=0.3
    const val GRUDGE_DAYS=14.0
    const val GRUDGE_CLOSENESS=0.2
    const val REPEAT_WINDOW_DAYS=28.0
    const val REPEAT_SLOW=1.5
    const val REPEAT_SLOW_MAX=2.0
    const val GRUDGE_FLOOR_ENABLED=false
    const val GRUDGE_FLOOR_RATIO=0.1
    const val CARE_WINDOW_H=24.0
    const val CARE_SCALE=3.0
    const val CARE_MAX_EXTRA=1.0
    const val APOLOGY_SURFACE=0.4
    const val FULFILL_GRUDGE=0.5
    const val OPPOSE=0.26
    // 小星已确认：低落触发门槛为 0.15。
    const val LOW_TRIGGER=0.15
    const val LOW_AMOUNT=0.3
    const val MISS_BASE_H=24.0
    const val MISS_CHAT=0.2
    const val MISS_REACH=0.6
    const val THETA_MAX=0.95
    const val THETA_GRUDGE=0.5

    fun expectation(closeness:Double)=0.5+0.5*closeness
    fun thetaBase(silenceDays:Double)=(if(silenceDays<=2.0)
        0.40+0.20*(silenceDays-1.0) else 0.60+(0.20/1.5)*(silenceDays-2.0)).coerceIn(0.30,0.90)
    fun theta(base:Double,grudge:Double)=minOf(THETA_MAX,base+THETA_GRUDGE*grudge)
    fun careMultiplier(n:Int)=1+CARE_MAX_EXTRA*(1-exp(-n/CARE_SCALE))
    fun grudgeHalfDays(closeness:Double,slow:Double)=GRUDGE_DAYS*slow/(1+GRUDGE_CLOSENESS*closeness)
    fun missingRate(closeness:Double)=ln(2.0)/MISS_BASE_H*(1+closeness)
    fun grudgeFloor(kind:String?,initial:Double,enabled:Boolean=GRUDGE_FLOOR_ENABLED)=
        if(enabled&&kind=="V")initial*GRUDGE_FLOOR_RATIO else 0.0
}
