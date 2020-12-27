<?php

require("lib/utils.php");
require("lib/nusoap.php");

$nsc = startEtapestrySession();

$request = array();
$request["start"] = 0;
$request["count"] = 100;
$request["query"] = "Phase1::All Active Members";

$response = $nsc->call("getExistingQueryResults", array($request));

function process($response) {
  foreach ($response as $v) foreach ($v as $value) {
    echo "\"" . $value["name"] . "\",\"";
    $arr = $value["phones"];
    foreach ($arr as $t)  {
	if ($t["type"] != "Twitter Account") echo $t["number"] . " ";
    }
    echo "\",\"" . $value["donorRoleRef"] ; // will be handy for querying donation history
    echo "\",\"" . $value["address"] . "\",\"" . $value["city"] . "\",\"" . $value["country"] . "\",\"". $value["postalCode"] . "\"\n";
  }
}
process($response);

if ($response["pages"] > 1) {

  do {

    $response = $nsc->call("getNextQueryResults", array());

    process($response);

    $hasMore = $nsc->call("hasMoreQueryResults", array());

  } while ($hasMore);
}
stopEtapestrySession($nsc);
?>
