package app.lianxu.independent

internal enum class FirstEncounterPhase { CLOSED, OPENING, ENTERING, READY }

/** Existing application pages; creating or restoring still belongs to those flows. */
internal enum class FirstEncounterDestination(val page:String) {
    CREATE("create"), RESTORE("role-archive")
}

/**
 * Presentation state only. The root UI retains it while an existing create/restore page is open.
 */
internal data class FirstEncounterState(
    val phase:FirstEncounterPhase=FirstEncounterPhase.CLOSED,
    val destination:FirstEncounterDestination?=null,
    val dismissed:Boolean=false
){
    init{require(destination==null||phase==FirstEncounterPhase.READY)}

    val canChoose get()=phase==FirstEncounterPhase.READY&&destination==null

    fun shouldShow(loaded:Boolean,hasRoles:Boolean,page:String)=loaded&&!hasRoles&&page=="home"&&!dismissed

    fun dismiss()=copy(dismissed=true)

    fun start():FirstEncounterState=if(phase==FirstEncounterPhase.CLOSED)copy(phase=FirstEncounterPhase.OPENING)else this
    fun doorOpened():FirstEncounterState=if(phase==FirstEncounterPhase.OPENING)copy(phase=FirstEncounterPhase.ENTERING)else this
    fun entered():FirstEncounterState=if(phase==FirstEncounterPhase.ENTERING)copy(phase=FirstEncounterPhase.READY)else this

    /** Also handles reduced motion becoming active during either animation. */
    fun withoutMotion():FirstEncounterState=if(phase==FirstEncounterPhase.READY)this else copy(phase=FirstEncounterPhase.READY)

    /** Only this transition emits a page request; reading retained state never emits navigation. */
    fun choose(target:FirstEncounterDestination):FirstEncounterChoice=if(canChoose){
        FirstEncounterChoice(copy(destination=target),target)
    }else FirstEncounterChoice(this)

    /** Cancelling either existing page returns to the settled view, without replaying the door. */
    fun returned():FirstEncounterState=if(destination!=null)copy(destination=null)else this

    /** An interrupted transition restores to usable choices, never backwards to a closed door. */
    fun restored():FirstEncounterState=if(phase==FirstEncounterPhase.OPENING||phase==FirstEncounterPhase.ENTERING)withoutMotion()else this
}

internal data class FirstEncounterChoice(val state:FirstEncounterState,val navigateTo:FirstEncounterDestination?=null)
