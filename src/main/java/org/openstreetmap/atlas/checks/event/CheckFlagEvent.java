package org.openstreetmap.atlas.checks.event;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.atlas.checks.base.Check;
import org.openstreetmap.atlas.checks.flag.CheckFlag;
import org.openstreetmap.atlas.geography.geojson.GeoJsonBuilder;
import org.openstreetmap.atlas.geography.geojson.GeoJsonBuilder.LocationIterableProperties;
import org.openstreetmap.atlas.geography.geojson.GeoJsonObject;
import org.openstreetmap.atlas.tags.HighwayTag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Wraps a {@link CheckFlag} for submission to the {@link EventService} for handling {@link Check}
 * results
 *
 * @author mkalender
 */
public final class CheckFlagEvent extends Event
{
    private static final GeoJsonBuilder GEOJSON_BUILDER = new GeoJsonBuilder();

    private final String checkName;
    private final CheckFlag flag;

    /**
     * Converts give {@link CheckFlag} to {@link GeoJsonObject} with additional key-value parameters
     *
     * @param flag
     *            {@link CheckFlag} to convert to {@link GeoJsonObject}
     * @param additionalProperties
     *            additional key-value parameters to be added in "properties" element of the
     *            top-level JSON object
     * @return {@link GeoJsonObject} created from {@link CheckFlag}
     */
    public static JsonObject flagToFeature(final CheckFlag flag,
            final Map<String, String> additionalProperties)
    {
        final JsonObject flagProperties = new JsonObject();
        flagProperties.addProperty("instructions", flag.getInstructions());

        // Add additional properties
        additionalProperties.forEach(flagProperties::addProperty);

        final JsonObject feature;
        final List<LocationIterableProperties> geometries = flag.getLocationIterableProperties();
        if (geometries.size() == 1)
        {
            feature = GEOJSON_BUILDER.create(geometries.get(0));
        }
        else
        {
            feature = GEOJSON_BUILDER.createGeometryCollection(geometries).jsonObject();
        }

        final JsonArray featureProperties = new JsonArray();
        final Set<JsonElement> featureOsmIds = new HashSet<>();
        geometries.stream().forEach(
                geometry -> Optional.ofNullable(geometry.getProperties()).ifPresent(propertyMap ->
                {
                    final JsonObject properties = new JsonObject();
                    propertyMap.forEach(properties::addProperty);
                    featureProperties.add(properties);

                    Optional.ofNullable(properties.get("osmid")).ifPresent(featureOsmIds::add);
                }));
        final JsonArray uniqueFeatureOsmIds = new JsonArray();
        featureOsmIds.forEach(uniqueFeatureOsmIds::add);

        // Override name property if able to add a decorator to the name
        CheckFlagEvent.featureDecorator(featureProperties)
                .ifPresent(decorator -> flagProperties.addProperty("name",
                        String.format("%s (%s)",
                                Optional.ofNullable(flagProperties.getAsJsonPrimitive("name"))
                                        .map(JsonPrimitive::getAsString).orElse("Task"),
                                decorator)));

        // Reference properties lost during GeoJson conversion
        flagProperties.add("feature_properties", featureProperties);
        flagProperties.add("feature_osmids", uniqueFeatureOsmIds);
        flagProperties.addProperty("feature_count", geometries.size());

        feature.addProperty("id", flag.getIdentifier());
        feature.add("properties", flagProperties);
        return feature;
    }

    /**
     * Converts given {@link CheckFlag} to {@link JsonObject} with additional key-value parameters
     *
     * @param flag
     *            {@link CheckFlag} to convert to {@link JsonObject}
     * @param additionalProperties
     *            additional key-value parameters to be added in "properties" element of the
     *            top-level JSON object
     * @return {@link JsonObject} created from {@link CheckFlag}
     */
    public static JsonObject flagToJson(final CheckFlag flag,
            final Map<String, String> additionalProperties)
    {
        final JsonObject flagJson = GEOJSON_BUILDER.create(flag.getLocationIterableProperties())
                .jsonObject();
        final JsonObject flagPropertiesJson = new JsonObject();
        flagPropertiesJson.addProperty("id", flag.getIdentifier());
        flagPropertiesJson.addProperty("instructions", flag.getInstructions());

        // Add additional properties
        additionalProperties.forEach(flagPropertiesJson::addProperty);

        // Add properties to the previously generate geojson
        flagJson.add("properties", flagPropertiesJson);
        return flagJson;
    }

    /**
     * Extracts a decorator based on the collective features properties. Currently the only
     * decoration is the highest class highway tag withing all of the feature properties for flags
     * involving Edges.
     */
    private static Optional<String> featureDecorator(final JsonArray featureProperties)
    {
        HighwayTag highestHighwayTag = null;
        for (final JsonElement featureProperty : featureProperties)
        {
            final HighwayTag baslineHighwayTag = highestHighwayTag == null ? HighwayTag.NO
                    : highestHighwayTag;
            highestHighwayTag = Optional
                    .ofNullable(((JsonObject) featureProperty).getAsJsonPrimitive(HighwayTag.KEY))
                    .map(JsonPrimitive::getAsString).map(String::toUpperCase)
                    .map(HighwayTag::valueOf).filter(baslineHighwayTag::isLessImportantThan)
                    .orElse(highestHighwayTag);
        }
        return Optional.ofNullable(highestHighwayTag)
                .map(tag -> String.format("%s=%s", HighwayTag.KEY, tag.getTagValue()));
    }

    /**
     * Construct a {@link CheckFlagEvent}
     *
     * @param checkName
     *            name of the check that created this event
     * @param flag
     *            {@link CheckFlag} generated within this event
     */
    public CheckFlagEvent(final String checkName, final CheckFlag flag)
    {
        this.checkName = checkName;
        this.flag = flag;
    }

    /**
     * @return {@link CheckFlag} generated by the check
     */
    public CheckFlag getCheckFlag()
    {
        return this.flag;
    }

    /**
     * @return Name of the check that generated this event
     */
    public String getCheckName()
    {
        return this.checkName;
    }

    /**
     * @return GeoJson Feature representation
     */
    public JsonObject toGeoJsonFeature()
    {
        final Map<String, String> contextualProperties = new HashMap<>();
        contextualProperties.put("name",
                this.getCheckFlag().getChallengeName().orElse(this.getCheckName()));
        contextualProperties.put("generator", "AtlasChecks");
        contextualProperties.put("timestamp", this.getTimestamp().toString());

        // Generate json for check flag with given contextual properties
        return flagToFeature(this.getCheckFlag(), contextualProperties);
    }

    /**
     * @return {@link JsonObject} form of the GeoJson FeatureCollection representation
     */
    public JsonObject toGeoJsonFeatureCollection()
    {
        final Map<String, String> contextualProperties = new HashMap<>();
        contextualProperties.put("generator", this.getCheckName());
        contextualProperties.put("timestamp", this.getTimestamp().toString());

        // Generate json for check flag with given contextual properties
        return flagToJson(this.getCheckFlag(), contextualProperties);
    }

    /**
     * @return {@link String} form of the GeoJson FeatureCollection representation
     */
    @Override
    public String toString()
    {
        return this.toGeoJsonFeatureCollection().toString();
    }
}
