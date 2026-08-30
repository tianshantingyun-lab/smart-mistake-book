"""Fix all LearningLedgerEvent when-exhaustive errors in LearningProjector.kt."""
from pathlib import Path

p = Path(r"W:\core\domain\src\main\kotlin\com\tingyun\smartmistakebook\core\domain\LearningProjector.kt")
t = p.read_text(encoding="utf-8")
lines = t.split("\n")

# Track which lines to insert and where
fixes = []
for i, line in enumerate(lines):
    stripped = line.strip()
    # When branch for presentationIds: add ChatEvidenceSubmitted alongside TutorAnswerExposureOutcome
    if "is TutorAnswerExposureOutcome -> null" in line:
        fixes.append((i, line.replace(
            "is TutorAnswerExposureOutcome -> null",
            "is TutorAnswerExposureOutcome,\nis ChatEvidenceSubmitted,\n-> null",
        )))
    # Fresh-record when: add ChatEvidenceSubmitted alongside TutorAnswerExposureOutcome
    elif "is TutorAnswerExposureOutcome ->" in line and "appliedTutorAnswerExposureRecords" in line:
        # This is a multi-line when branch — need to add after it
        # We'll handle by looking at the closing brace on next line
        pass
    # General exhaustive when: mark for manual review
    if "must be exhaustive" in line:
        pass

# Strategy: for each "is TutorAnswerExposureOutcome" line in a when that also has
# "is Attempt ->" and "is AnswerRevealOutcome ->", add ChatEvidenceSubmitted branch

# Simply find each `when (event)` block and add `is ChatEvidenceSubmitted ->` branch
# For the key positions identified by the compiler:
fix_points = []
in_when = False
when_start = -1
for i, line in enumerate(lines):
    s = line.strip()
    if s == "when (event) {" or s.startswith("when (event) {"):
        when_start = i
    if "is TutorAnswerExposureOutcome" in s and when_start >= 0:
        fix_points.append((i, s))
    if "is AttemptCorrection" in s and when_start >= 0:
        fix_points.append((i, s))

# Print diagnostics
for i, s in fix_points:
    print(f"line {i+1}: {s.strip()[:80]}")

# Apply fixes at specific positions
# Line numbers from compiler (0-indexed): 96, 139, 151, 233, 380, 501, 579
# These are 0-indexed positions of when branches needing ChatEvidenceSubmitted

# Fix 97 (0-idx 96): presentationIds when — null branch
if "is TutorAnswerExposureOutcome -> null" in t:
    t = t.replace(
        "is TutorAnswerExposureOutcome -> null",
        "is TutorAnswerExposureOutcome,\n                is ChatEvidenceSubmitted,\n                -> null",
        1,
    )

# Fix 140: fresh-record when — add ChatEvidenceSubmitted record lookup
old_140 = """                is TutorAnswerExposureOutcome ->
                    previous.appliedTutorAnswerExposureRecords[event.outcomeId]?.let {
                        it.eventSequence to it.canonicalFingerprint
                    }
            }"""
new_140 = """                is TutorAnswerExposureOutcome ->
                    previous.appliedTutorAnswerExposureRecords[event.outcomeId]?.let {
                        it.eventSequence to it.canonicalFingerprint
                    }
                is ChatEvidenceSubmitted ->
                    previous.appliedChatEvidenceRecords[event.evidenceId]?.let {
                        it.eventSequence to it.canonicalFingerprint
                    }
            }"""
if old_140 in t:
    t = t.replace(old_140, new_140, 1)

# Fix 152: idExistsAsAnotherType when
old_152 = """                is TutorAnswerExposureOutcome -> event.outcomeId in previous.appliedAttemptRecords ||
                    event.outcomeId in previous.appliedTutorAnswerExposureRecords
            }"""
new_152 = """                is TutorAnswerExposureOutcome -> event.outcomeId in previous.appliedAttemptRecords ||
                    event.outcomeId in previous.appliedTutorAnswerExposureRecords
                is ChatEvidenceSubmitted -> event.evidenceId in previous.appliedAttemptRecords
            }"""
if old_152 in t:
    t = t.replace(old_152, new_152, 1)

# Fix 234: full-replay second pass when — add ChatEvidenceSubmitted branch that
# updates masteryStates and chatEvidenceRecords
old_234 = """                is TutorAnswerExposureOutcome -> {
                    memoryStates[event.practiceUnitId] = projectTutorAnswerExposure(
                        previous = memoryStates[event.practiceUnitId],
                        outcome = event,
                        effectiveAtEpochMillis = effectiveAt,
                    )
                    tutorExposureRecords[event.outcomeId] = AppliedTutorAnswerExposureRecord(
                        outcomeId = event.outcomeId,
                        exposureId = event.exposureId,
                        canonicalFingerprint = LearningLedgerFingerprint.tutorAnswerExposure(event),
                        eventSequence = event.eventSequence,
                    )
                }
                is AttemptCorrection -> {"""
new_234 = """                is ChatEvidenceSubmitted -> {
                    masteryStates[event.knowledgeNodeId] = projectChatEvidence(
                        previous = masteryStates[event.knowledgeNodeId],
                        event = event,
                        effectiveAtEpochMillis = effectiveAt,
                    )
                    chatEvidenceRecords[event.evidenceId] = event
                }
                is TutorAnswerExposureOutcome -> {
                    memoryStates[event.practiceUnitId] = projectTutorAnswerExposure(
                        previous = memoryStates[event.practiceUnitId],
                        outcome = event,
                        effectiveAtEpochMillis = effectiveAt,
                    )
                    tutorExposureRecords[event.outcomeId] = AppliedTutorAnswerExposureRecord(
                        outcomeId = event.outcomeId,
                        exposureId = event.exposureId,
                        canonicalFingerprint = LearningLedgerFingerprint.tutorAnswerExposure(event),
                        eventSequence = event.eventSequence,
                    )
                }
                is AttemptCorrection -> {"""
if old_234 in t:
    t = t.replace(old_234, new_234, 1)

# Fix 381: full-replay validation when — add ChatEvidenceSubmitted -> Unit
old_381 = "                is TutorAnswerExposureOutcome -> Unit"
if t.count(old_381) == 1:
    t = t.replace(old_381, old_381 + "\n                is ChatEvidenceSubmitted -> Unit", 1)

# Fix 502/507: projectChatEvidence function + chatEvidenceRecords map declaration
# These were not successfully added earlier — add them now
if "chatEvidenceRecords" not in t:
    old_records = "        val tutorExposureRecords = mutableMapOf<String, AppliedTutorAnswerExposureRecord>()"
    # Find the LAST occurrence (incremental projection, not full replay)
    idx = t.rfind(old_records)
    if idx >= 0:
        t = t[:idx + len(old_records)] + "\n        val chatEvidenceRecords = mutableMapOf<String, ChatEvidenceSubmitted>()" + t[idx + len(old_records):]

# Add appliedChatEvidenceRecords to snapshot construction
old_snap = "            appliedTutorAnswerExposureRecords = boundedTutorAnswerExposureRecords(tutorExposureRecords),"
if old_snap in t and "appliedChatEvidenceRecords" not in t:
    t = t.replace(old_snap, old_snap + "\n            appliedChatEvidenceRecords = boundedChatEvidenceRecords(chatEvidenceRecords),", 1)

# Add appliedChatEvidenceIds to LearningProjectionResult
old_result = "            appliedTutorAnswerExposureOutcomeIds = tutorExposureRecords.keys.toSet(),"
if old_result in t and "appliedChatEvidenceIds" not in t:
    t = t.replace(old_result, old_result + "\n            appliedChatEvidenceIds = chatEvidenceRecords.keys.toSet(),", 1)

# Add projectChatEvidence function after NEGATIVE_LEARNING_RATE constant
old_rate = "        private const val NEGATIVE_LEARNING_RATE = 0.42"
if old_rate in t and "projectChatEvidence" not in t:
    fn = """
        private fun projectChatEvidence(
            previous: KnowledgeMasteryState?,
            event: ChatEvidenceSubmitted,
            effectiveAtEpochMillis: Long,
        ): KnowledgeMasteryState {
            val probability = previous?.masteryScore ?: INITIAL_MASTERY_PROBABILITY
            val positive = event.direction == LearningEvidenceDirection.POSITIVE
            val weight = event.weight
            // Chat evidence uses the same learning rates but capped weights —
            // the model chose when to submit, the formula still owns the value.
            val updatedProbability = if (positive) {
                probability + (1.0 - probability) * POSITIVE_LEARNING_RATE * weight
            } else {
                probability - probability * NEGATIVE_LEARNING_RATE * weight
            }.coerceIn(0.0, 1.0)
            val evidenceMass = (previous?.evidenceMass ?: 0.0) + weight
            val lowerBound = masteryLowerBound(updatedProbability, evidenceMass)
            val status = when {
                evidenceMass < 1.0 -> MasteryStatus.UNKNOWN
                clearlyMastered(lowerBound, evidenceMass, emptyList(), null, null, effectiveAtEpochMillis) ->
                    MasteryStatus.MASTERED
                else -> MasteryStatus.LEARNING
            }
            return KnowledgeMasteryState(
                knowledgeNodeId = event.knowledgeNodeId,
                masteryScore = updatedProbability,
                conservativeMasteryScore = lowerBound,
                evidenceMass = evidenceMass,
                independentCorrectObservations = previous?.independentCorrectObservations.orEmpty(),
                lastIndependentErrorAtEpochMillis = previous?.lastIndependentErrorAtEpochMillis,
                lastIndependentErrorSequence = previous?.lastIndependentErrorSequence,
                status = status,
                calibrationSupport = previous?.calibrationSupport ?: CalibrationSupport.UNKNOWN,
                projectorVersion = VERSION,
                checkpointSequence = event.eventSequence,
            )
        }"""
    t = t.replace(old_rate, old_rate + "\n" + fn, 1)

# Fix boundedChatEvidenceRecords — add helper if not present
if "boundedChatEvidenceRecords" not in t:
    # Add alongside other bounded helpers
    old_bounded = "        private fun boundedTutorAnswerExposureRecords("
    if old_bounded in t:
        # Find the end of that function and add ours
        pass  # We'll handle this if compile complains

p.write_text(t, encoding="utf-8")
print("projector fixes applied")
