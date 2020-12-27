<?php

require("lib/utils.php");
require("lib/nusoap.php");

$nsc = startEtapestrySession();

$request = array();
$request["start"] = 0;
$request["count"] = 100;
$request["query"] = "Phase1::All Open Centers";

// Invoke getExistingQueryResults method
$response = $nsc->call("getExistingQueryResults", array($request));

foreach ($response as $v) foreach ($v as $value) {
    $arr = $value["accountDefinedValues"];
    echo "\"" . $value["name"] . "\",";
    foreach ($arr as $t)  {
        if ($t["fieldName"] == "Open Date") echo "\"" . explode('/',$t["value"])[2] . "\",";
    }
    echo "\n";
}


stopEtapestrySession($nsc);
?>
