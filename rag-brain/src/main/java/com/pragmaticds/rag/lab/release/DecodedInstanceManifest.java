package com.pragmaticds.rag.lab.release;

/** A stored manifest decoded according to its own version, never silently upgraded on read. */
public sealed interface DecodedInstanceManifest
        permits DecodedInstanceManifest.V1Income, DecodedInstanceManifest.V2 {

    record V1Income(LabReleaseManifest manifest) implements DecodedInstanceManifest {}

    record V2(InstanceReleaseManifest manifest) implements DecodedInstanceManifest {}
}
